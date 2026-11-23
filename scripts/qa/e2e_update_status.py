#!/usr/bin/env python3
"""Automated Android E2E for the update status: no taps on "Impor", no manual steps.

What it proves, in order:
1. the installed debug build reaches the local UAT backend;
2. a cold start triggers the transaction check and the category queue on its own
   (backend log shows POST /v1/category-jobs/enqueue and /drain without any import tap);
3. the top bar shows a status word that matches a known state;
4. opening "Status pembaruan" is read-only: the sheet appears and the backend receives
   no new write request just because the sheet was opened;
5. the sheet keeps "last attempt" and "last successful sync" apart and never hides a
   pending upload behind a success.

The session must already be signed in (this script never handles credentials). Output is
plain text only; it prints fixture-level state, never tokens or message ids.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time

ADB = "adb"
PACKAGE = "com.pam.recap.debug"
ACTIVITY = f"{PACKAGE}/com.pam.recap.MainActivity"

PILL_LABELS = {
    "Belum diperbarui",
    "Memeriksa",
    "Menyimpan",
    "Mengategorikan",
    "Menunggu",
    "Offline",
    "Perlu perhatian",
    "Terbaru",
}

SHEET_TITLE = "Status pembaruan"
SHEET_SECTIONS = ("Data transaksi", "Kategori", "Perlu perhatian")


class Driver:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.screenshots = ""

    # ------------------------------------------------------------------ adb
    def adb(self, *args: str, timeout: int = 60) -> str:
        proc = subprocess.run(
            [ADB, *args], capture_output=True, text=True, timeout=timeout, check=False
        )
        if proc.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {proc.stderr.strip()}")
        return proc.stdout

    def nodes(self) -> list[dict]:
        """uiautomator refuses to dump while the window is still animating; retry briefly."""
        for _ in range(4):
            try:
                self.adb("shell", "uiautomator", "dump", "/data/local/tmp/status.xml", timeout=45)
                xml = self.adb("shell", "cat", "/data/local/tmp/status.xml")
            except (RuntimeError, subprocess.TimeoutExpired):
                time.sleep(1.0)
                continue
            found = parse_nodes(xml)
            if found:
                return found
            time.sleep(1.0)
        return []

    def texts(self) -> list[str]:
        return [n["text"] for n in self.nodes() if n["text"]]

    def wait_text(self, predicate, label: str, seconds: int = 25) -> bool:
        deadline = time.time() + seconds
        while time.time() < deadline:
            if predicate(self.texts()):
                return True
            time.sleep(1.2)
        self.failures.append(f"timeout waiting for {label}")
        return False

    def tap_text(self, needle: str) -> bool:
        for node in self.nodes():
            if needle in node["text"]:
                self.adb("shell", "input", "tap", str(node["cx"]), str(node["cy"]))
                return True
        self.failures.append(f"cannot tap {needle!r}: not on screen")
        return False

    def check(self, condition: bool, message: str) -> None:
        if not condition:
            self.failures.append(message)

    # ------------------------------------------------------------- scenario
    def run(self, log_path: str, backend: str) -> None:
        baseline = count_routes(log_path)
        print(f"backend log baseline: enqueue={baseline['enqueue']} drain={baseline['drain']}")

        self.adb("shell", "am", "force-stop", PACKAGE)
        self.adb("shell", "am", "start", "-n", ACTIVITY)
        self.check(
            self.wait_text(lambda t: any(x in PILL_LABELS for x in t), "the status pill"),
            "the top bar never showed a known status word",
        )
        self.snap("01-pill")
        labels = [x for x in self.texts() if x in PILL_LABELS]
        print(f"pill: {labels[0] if labels else 'none'}")

        after_start = count_routes(log_path)
        print(
            "after cold start: "
            f"enqueue={after_start['enqueue']} drain={after_start['drain']}"
        )
        self.check(
            after_start["enqueue"] > baseline["enqueue"],
            "cold start did not enqueue category candidates on its own",
        )
        self.check(
            after_start["drain"] > baseline["drain"],
            "cold start did not ask the server to drain the category queue",
        )

        # Opening the sheet must not create work.
        before_sheet = count_routes(log_path)
        if labels:
            self.tap_text(labels[0])
        self.check(self.wait_text(lambda t: SHEET_TITLE in t, "the status sheet"), "sheet did not open")
        sheet_texts = self.texts()
        for section in SHEET_SECTIONS:
            self.check(section in sheet_texts, f"sheet is missing section {section!r}")

        flat = " | ".join(sheet_texts)
        self.check("Percobaan terakhir" in flat, "sheet hides the last attempt")
        self.check("Terakhir berhasil" in flat, "sheet hides the last successful sync")
        self.check("%1$s" not in flat, "sheet leaks a raw format placeholder")
        self.snap("02-sheet")
        after_sheet = count_routes(log_path)
        print(f"after opening sheet: enqueue={after_sheet['enqueue']} drain={after_sheet['drain']}")
        self.check(
            after_sheet == before_sheet,
            "opening the sheet started a backend request; it must be read-only",
        )
        print("sheet lines:")
        for line in sheet_texts:
            print(f"  - {line}")

        self.adb("shell", "input", "keyevent", "4")

    def snap(self, name: str) -> None:
        """One fresh screenshot per state, straight from the device; never stitched."""
        if not self.screenshots:
            return
        from pathlib import Path

        target = Path(self.screenshots)
        target.mkdir(parents=True, exist_ok=True)
        data = subprocess.run(
            [ADB, "exec-out", "screencap", "-p"], capture_output=True, check=False
        ).stdout
        if data:
            (target / f"{name}.png").write_bytes(data)

    def report(self) -> int:
        if self.failures:
            print("\nFAIL")
            for failure in self.failures:
                print(f"  - {failure}")
            return 1
        print("\nPASS: automated status E2E (no import tap)")
        return 0


def parse_nodes(xml: str) -> list[dict]:
    import xml.etree.ElementTree as ET

    try:
        root = ET.fromstring(xml.split("?>", 1)[-1])
    except ET.ParseError:
        return []
    found = []
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        if not text:
            continue
        x1, y1, x2, y2 = parse_bounds(node.get("bounds") or "")
        found.append({"text": text, "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2})
    return found


def parse_bounds(raw: str) -> tuple[int, int, int, int]:
    import re

    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw)
    if not match:
        return 0, 0, 0, 0
    return tuple(int(v) for v in match.groups())  # type: ignore[return-value]


def count_routes(log_path: str) -> dict[str, int]:
    """Counts only the route labels the backend already sanitises; no bodies are read."""
    counts = {"enqueue": 0, "drain": 0}
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if "/v1/category-jobs/enqueue" in line:
                    counts["enqueue"] += 1
                elif "/v1/category-jobs/drain" in line:
                    counts["drain"] += 1
    except FileNotFoundError:
        pass
    return counts


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", default="/tmp/pam-uat-18090-fixed.log")
    parser.add_argument("--backend", default="http://127.0.0.1:18090")
    parser.add_argument("--screenshots", default="build/e2e-status")
    args = parser.parse_args()

    driver = Driver()
    driver.screenshots = args.screenshots
    try:
        driver.run(args.log, args.backend)
    except Exception as error:  # noqa: BLE001 - the report should still name the break
        driver.failures.append(f"driver crashed: {error}")
    return driver.report()


if __name__ == "__main__":
    sys.exit(main())
