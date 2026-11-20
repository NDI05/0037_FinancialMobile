#!/usr/bin/env bash
# Menyiapkan lingkungan uji untuk aplikasi PAM di perangkat yang tersambung.
#
#   1. memastikan ada perangkat (USB atau wireless debugging)
#   2. memasang tunnel `adb reverse` supaya 127.0.0.1:8080 di HP menuju laptop
#   3. memastikan backend hidup di port 8080
#
# Tunnel `adb reverse` HILANG setiap kali koneksi perangkat berubah (cabut kabel, pindah ke
# wireless, HP tidur). Jalankan skrip ini setiap kali itu terjadi, atau sebelum demo.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$ROOT/backend/.dev-server.log"

if [[ "${1:-}" == "--logs" || "${1:-}" == "-l" ]]; then
  if [[ ! -f "$LOG" ]]; then
    echo "Belum ada log di $LOG — jalankan ./scripts/dev.sh dulu." >&2
    exit 1
  fi
  echo "Menampilkan log backend (Ctrl+C untuk berhenti melihat, server tetap jalan)."
  exec tail -f "$LOG"
fi
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PORT="${PAM_PORT:-8080}"

if [[ ! -x "$ADB" ]]; then
  echo "adb tidak ditemukan di $ADB" >&2
  echo "Set ADB=/path/ke/adb atau tambahkan platform-tools ke PATH." >&2
  exit 1
fi

echo "== perangkat =="
"$ADB" devices -l | sed '/^List of devices/d' | sed '/^$/d' || true
if ! "$ADB" devices | grep -qw device; then
  echo
  echo "Tidak ada perangkat siap pakai." >&2
  echo "  - USB   : pilih mode 'Transfer file' dan setujui dialog debugging" >&2
  echo "  - Nirkabel: Pengaturan > Opsi pengembang > Debug wireless, lalu adb pair/connect" >&2
  exit 1
fi

echo
echo "== tunnel adb reverse =="
"$ADB" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
"$ADB" reverse --list | sed 's/^/  /'

echo
echo "== backend =="
if [[ "${PAM_SKIP_BACKEND:-0}" == "1" ]]; then
  echo "  dilewati (PAM_SKIP_BACKEND=1)"
elif curl -fsS --max-time 2 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then
  echo "  sudah hidup di port $PORT"
else
  echo "  menyalakan backend…"
  ( cd "$ROOT/backend" && nohup npm start >"$LOG" 2>&1 & )
  for _ in $(seq 1 30); do
    sleep 1
    if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then break; fi
  done
  if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then
    echo "  hidup (log: backend/.dev-server.log — lihat dengan: ./scripts/dev.sh --logs)"
  else
    echo "  GAGAL menyala — lihat backend/.dev-server.log" >&2
    exit 1
  fi
fi

echo
echo "== uji dari perangkat =="
if "$ADB" shell "toybox nc -w 5 127.0.0.1 $PORT </dev/null >/dev/null 2>&1"; then
  echo "  port $PORT terjangkau dari HP"
else
  echo "  port $PORT TIDAK terjangkau dari HP — coba cabut/colok ulang lalu jalankan lagi" >&2
  exit 1
fi

echo
echo "Lihat log backend : ./scripts/dev.sh --logs"
echo "Hentikan backend  : pkill -f 'tsx src/server.ts'"
echo
echo "Siap. Jalankan aplikasi dari Android Studio atau:"
echo "  $ADB shell am start -n com.pam.recap.debug/com.pam.recap.MainActivity"
