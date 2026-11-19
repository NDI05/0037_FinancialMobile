#!/usr/bin/env bash
# Smoke test: seluruh happy path terhadap backend yang sudah hidup.
#
#   npm start                     # terminal 1 (LLM palsu, SQLite lokal)
#   bash scripts/smoke.sh         # terminal 2
#
# Setiap langkah harus 2xx; skrip keluar non-zero bila ada yang gagal.
# Set BASE_URL untuk menunjuk port lain, INTERNAL_SWEEP_TOKEN untuk ikut menguji sweep.
set -u

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
# Setiap run memakai akun baru, supaya bisa dijalankan berulang di database yang sama
# (permintaan penghapusan sebelumnya akan memblokir akun lama dengan 409).
SMOKE_USER="${SMOKE_USER:-smoke-$(date +%s)}"
BODY=/tmp/pam-smoke-body
HEADERS=/tmp/pam-smoke-headers
FAILURES=0
STEP=0
LAST_CODE=""
TOKEN=""
FIRST_ANALYSIS=""
SECOND_ANALYSIS=""

say() { printf '\n\033[1m%s\033[0m\n' "$1"; }

print_body() {
  node -e '
    const fs = require("node:fs");
    const raw = fs.readFileSync(process.argv[1], "utf8");
    if (!raw) { console.log("(kosong)"); process.exit(0); }
    try { console.log(JSON.stringify(JSON.parse(raw), null, 2)); }
    catch { console.log(raw); }
  ' "$BODY"
}

track() {
  case "$LAST_CODE" in
    2*) ;;
    *) FAILURES=$((FAILURES + 1)); printf '\033[31m  !! langkah %s gagal (HTTP %s)\033[0m\n' "$STEP" "$LAST_CODE" >&2 ;;
  esac
}

# request <METHOD> <PATH> [BODY] [TOKEN] [IDEMPOTENCY_KEY]
request() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}" idem="${5:-}"
  local args=(-sS -X "$method" -H 'accept: application/json' -D "$HEADERS" -o "$BODY" -w '%{http_code}')
  [ -n "$body" ] && args+=(-H 'content-type: application/json' --data "$body")
  [ -n "$token" ] && args+=(-H "authorization: Bearer $token")
  [ -n "$idem" ] && args+=(-H "idempotency-key: $idem")
  STEP=$((STEP + 1))
  LAST_CODE="$(curl "${args[@]}" "$BASE_URL$path" 2>/dev/null)"
  printf '%s %s%s -> %s\n' "$method" "$path" "${idem:+ (Idempotency-Key: $idem)}" "$LAST_CODE"
  print_body
  track
}

json_field() {
  node -e '
    const fs = require("node:fs");
    const value = JSON.parse(fs.readFileSync(process.argv[1], "utf8"))[process.argv[2]];
    process.stdout.write(String(value));
  ' "$BODY" "$1"
}

# Payload agregat Februari 2026 (angka demo, satuan minor = rupiah × 100).
ANALYSIS_PAYLOAD='{
  "monthId": "2026-02",
  "inputRevision": 7,
  "payload": {
    "schemaVersion": 1,
    "monthId": "2026-02",
    "coverage": "COMPLETE",
    "grossInflowMinor": 585000000,
    "grossOutflowMinor": 287065000,
    "netCashFlowMinor": 297935000,
    "classifiedIncomeMinor": 575000000,
    "classifiedExpenseMinor": 249565000,
    "transactionCount": 18,
    "uncategorizedCount": 1,
    "manualEntryCount": 2,
    "nonIdrExcludedCount": 1,
    "categoryTotals": [
      { "categoryCode": "housing", "amountMinor": 90000000, "transactionCount": 2 },
      { "categoryCode": "food", "amountMinor": 25625000, "transactionCount": 4 },
      { "categoryCode": "transport", "amountMinor": 8550000, "transactionCount": 3 },
      { "categoryCode": "utilities", "amountMinor": 40000000, "transactionCount": 2 },
      { "categoryCode": "shopping", "amountMinor": 55775000, "transactionCount": 2 },
      { "categoryCode": "health", "amountMinor": 8990000, "transactionCount": 1 },
      { "categoryCode": "education", "amountMinor": 18900000, "transactionCount": 1 }
    ],
    "priorMonth": { "monthId": "2026-01", "expenseMinor": 180000000, "incomeMinor": 0 },
    "plan": null
  }
}'

SUMMARY_PAYLOAD="$(node -e '
  const payload = JSON.parse(process.argv[1]);
  process.stdout.write(JSON.stringify({ inputRevision: payload.inputRevision, payload: payload.payload }));
' "$ANALYSIS_PAYLOAD")"

say "1/9 health"
request GET /healthz

say "2/9 session ($SMOKE_USER, token dev tanpa Google)"
request POST /v1/sessions "{\"idToken\":\"dev:$SMOKE_USER:Andhika\"}"
TOKEN="$(json_field accessToken)"
if [ -z "$TOKEN" ] || [ "$TOKEN" = "undefined" ]; then
  printf '\nTidak ada accessToken — backend hidup dengan AUTH_DEV_FAKE_TOKENS=false?\n' >&2
  exit 1
fi

say "3/9 consent AI (FR-34)"
request POST /v1/consents '{"purpose":"AI_PROCESSING","decision":"GRANTED","noticeVersion":"v1"}' "$TOKEN"

say "4/9 simpan agregat 2026-02 (FR-33, terenkripsi)"
request PUT /v1/summaries/2026-02 "$SUMMARY_PAYLOAD" "$TOKEN"

say "5/9 analisis pertama (provider palsu)"
request POST /v1/analyses "$ANALYSIS_PAYLOAD" "$TOKEN" "smoke-1-$SMOKE_USER"
FIRST_ANALYSIS="$(cat "$BODY")"

say "6/9 analisis kedua dengan revision sama (cache FR-40)"
request POST /v1/analyses "$ANALYSIS_PAYLOAD" "$TOKEN" "smoke-2-$SMOKE_USER"
SECOND_ANALYSIS="$(cat "$BODY")"
if [ "$FIRST_ANALYSIS" = "$SECOND_ANALYSIS" ]; then
  printf '\033[32mcache: respons identik dengan panggilan pertama (provider tidak dipanggil lagi)\033[0m\n'
else
  printf '\033[33mcache: respons berbeda — periksa revision/promptVersion\033[0m\n'
fi

say "7/9 rencana anggaran 2026-03 (FR-42…FR-45)"
request PUT /v1/budget-plans/2026-03 '{
  "plan": {
    "expectedIncomeMinor": 287500000,
    "plannedSavingsMinor": 28750000,
    "categoryLimitTotals": [
      { "categoryCode": "housing", "amountMinor": 95000000, "transactionCount": 2 },
      { "categoryCode": "food", "amountMinor": 38675000, "transactionCount": 4 }
    ]
  }
}' "$TOKEN"
request GET /v1/budget-plans/2026-03 "" "$TOKEN"

say "8/9 status akun (FR-49)"
request GET /v1/me "" "$TOKEN"

say "9/9 penghapusan (FR-47/FR-48)"
request POST /v1/deletion-requests "" "$TOKEN"
DELETION_ID="$(json_field id)"
request GET "/v1/deletion-requests/$DELETION_ID" "" "$TOKEN"

if [ -n "${INTERNAL_SWEEP_TOKEN:-}" ]; then
  say "bonus: sweep (INTERNAL_SWEEP_TOKEN terisi)"
  STEP=$((STEP + 1))
  LAST_CODE="$(curl -sS -X POST -H "x-internal-token: $INTERNAL_SWEEP_TOKEN" -o "$BODY" -w '%{http_code}' "$BASE_URL/v1/internal/sweep")"
  printf 'POST /v1/internal/sweep -> %s\n' "$LAST_CODE"
  print_body
  track
  request GET "/v1/deletion-requests/$DELETION_ID" "" "$TOKEN"
fi

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf '\033[32mSmoke OK — %s langkah, semua 2xx.\033[0m\n' "$STEP"
  exit 0
fi
printf '\033[31mSmoke GAGAL — %s dari %s langkah tidak 2xx.\033[0m\n' "$FAILURES" "$STEP" >&2
exit 1
