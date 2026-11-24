#!/usr/bin/env bash
# Pemeriksaan kesiapan UAT perangkat (SSO + Gmail live) — hanya membaca, tidak mengubah apa pun.
#
# Script ini TIDAK meminta, mencetak, atau menyimpan password/OTP/secret/client ID. Tujuannya
# memberi tahu pemilik apa yang kurang sebelum menekan Run di Android Studio.
#
# Pemakaian:
#   scripts/verify/uat_readiness.sh \
#     --backend-url http://127.0.0.1:18090 \
#     [--serial <serial HP>] [--package com.pam.recap.debug] [--apk <path app-debug.apk>]
#
# Keluar 0 bila semua prasyarat otomatis siap; 2 bila ada prasyarat yang belum; 1 bila ada
# kegagalan nyata (mis. adb tidak bisa dijalankan).

set -u

BACKEND_URL="http://127.0.0.1:18090"
SERIAL=""
PACKAGE="com.pam.recap.debug"
APK=""
MISSING=()
FAILED=0

usage() {
  sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --backend-url) BACKEND_URL="${2:-}"; shift 2 ;;
    --serial) SERIAL="${2:-}"; shift 2 ;;
    --package) PACKAGE="${2:-}"; shift 2 ;;
    --apk) APK="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "argumen tidak dikenal: $1" >&2; usage ;;
  esac
done

say()   { printf '%-58s %s\n' "$1" "$2"; }
ok()    { say "$1" "OK"; }
warn()  { say "$1" "BELUM"; MISSING+=("$1"); }
bad()   { say "$1" "GAGAL"; FAILED=1; }

ADB_BIN="${ADB:-}"
if [ -z "$ADB_BIN" ]; then
  for cand in "${ANDROID_HOME:-}/platform-tools/adb" "$HOME/Library/Android/sdk/platform-tools/adb"; do
    if [ -x "$cand" ]; then ADB_BIN="$cand"; break; fi
  done
fi
if [ -z "$ADB_BIN" ]; then ADB_BIN="$(command -v adb 2>/dev/null || true)"; fi
if [ -z "$ADB_BIN" ] || [ ! -x "$ADB_BIN" ]; then
  bad "adb ditemukan"
else
  ok "adb ditemukan"
fi

http_code() { # url -> kode HTTP (000 bila tidak terhubung)
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$1" 2>/dev/null || true)"
  [ -n "$code" ] || code="000"
  printf '%s' "$code"
}

# 1) Backend hidup
code="$(http_code "$BACKEND_URL/readyz")"
if [ "$code" = "200" ]; then
  ok "backend /readyz (${BACKEND_URL})"
else
  warn "backend /readyz (${BACKEND_URL}) -> HTTP ${code}"
fi

# 2) Backend SSO aktif (prasyarat alur "Masuk dengan Google")
code="$(http_code "$BACKEND_URL/v1/auth/google/nonce")"
case "$code" in
  200) ok "backend SSO nonce (200 = aktif)" ;;
  503) warn "backend SSO nonce (503 = google_sso_disabled)" ;;
  000) warn "backend SSO nonce (backend tidak terhubung)" ;;
  *)   warn "backend SSO nonce (HTTP ${code})" ;;
esac

# 3) Rute Gmail ada (401 tanpa token = rute terpasang; bukan bukti koneksi/consent)
code="$(http_code "$BACKEND_URL/v1/gmail/status")"
if [ "$code" = "401" ]; then
  ok "rute /v1/gmail/status terpasang (401 tanpa token)"
elif [ "$code" = "503" ]; then
  warn "rute /v1/gmail/status (503 = Gmail dimatikan server)"
else
  warn "rute /v1/gmail/status (HTTP ${code}, harap 401 tanpa token)"
fi

# 4) HP fisik terhubung
PHONE_SERIAL="$SERIAL"
if [ -n "$ADB_BIN" ] && [ -x "$ADB_BIN" ]; then
  if [ -z "$PHONE_SERIAL" ]; then
    PHONE_SERIAL="$("$ADB_BIN" devices 2>/dev/null | awk '$2=="device" && $1 !~ /^emulator-/ {print $1; exit}')"
  fi
  if [ -n "$PHONE_SERIAL" ]; then
    ok "HP fisik terhubung (${PHONE_SERIAL})"
  else
    warn "HP fisik terhubung (tidak ada perangkat non-emulator)"
  fi
fi

# 5) Aplikasi terpasang + endpoint 18090 di-reverse (prasyarat UAT lewat HP)
if [ -n "$PHONE_SERIAL" ] && [ -n "$ADB_BIN" ] && [ -x "$ADB_BIN" ]; then
  if "$ADB_BIN" -s "$PHONE_SERIAL" shell pm list packages 2>/dev/null | grep -qx "package:$PACKAGE"; then
    ok "paket ${PACKAGE} terpasang"
  else
    warn "paket ${PACKAGE} terpasang"
  fi
  if "$ADB_BIN" -s "$PHONE_SERIAL" reverse --list 2>/dev/null | grep -q 'tcp:18090'; then
    ok "adb reverse tcp:18090"
  else
    warn "adb reverse tcp:18090"
  fi
fi

# 6) APK memuat client ID Web OAuth (hanya hitung kemunculan, nilai tidak dicetak)
if [ -n "$APK" ]; then
  if [ ! -f "$APK" ]; then
    bad "APK ada di --apk"
  elif ! command -v unzip >/dev/null 2>&1; then
    warn "unzip tersedia untuk memeriksa APK"
  else
    count="$(unzip -p "$APK" 'classes*.dex' 2>/dev/null | strings 2>/dev/null \
      | grep -c 'apps\.googleusercontent\.com' || true)"
    if [ "${count:-0}" -gt 0 ]; then
      ok "APK memuat client ID Web OAuth (>=1 kemunculan)"
    else
      warn "APK memuat client ID Web OAuth (build tanpa PAM_GOOGLE_SSO_CLIENT_ID)"
    fi
  fi
else
  say "APK memuat client ID Web OAuth" "lewat (--apk tidak diberikan)"
fi

# 7) Gate Rp0 tidak bisa diperiksa tanpa akses Billing pemilik — selalu manual.
say "GCP Rp0 (Billing tidak tertaut)" "manual, bukan otomatis"

echo
if [ "$FAILED" -ne 0 ]; then
  echo "HASIL: ada kegagalan teknis; perbaiki item GAGAL di atas."
  exit 1
fi
if [ "${#MISSING[@]}" -gt 0 ]; then
  echo "HASIL: belum siap UAT. Kurang ${#MISSING[@]} prasyarat:"
  for item in "${MISSING[@]}"; do echo "  - $item"; done
  echo "Panduan: docs/GOOGLE_SSO_OWNER_SETUP.md dan docs/delivery/FINAL_GATES_GMAIL_YEARLY_WORKFLOW_2026-11-24.md §4."
  exit 2
fi
echo "HASIL: prasyarat otomatis siap. Gate live (G25/G29/G36) tetap harus dijalankan pemilik dan dilaporkan terpisah."
