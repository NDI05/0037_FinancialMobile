#!/usr/bin/env bash
# Minimal Go backend smoke: register -> login -> consent -> category -> transaction -> summary.
# Requires a running server (default http://127.0.0.1:8080) and the invite code it was started with.
set -euo pipefail
BASE="${PAM_BASE_URL:-http://127.0.0.1:8080}"
INVITE="${PAM_INVITE_CODE:-pam-dev}"
EMAIL="smoke-$(date +%s)@example.test"
PASSWORD="synthetic-smoke-password"

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

code=$(curl -s -o /tmp/pam-go-register.json -w '%{http_code}' -X POST "$BASE/v1/accounts" \
  -H 'content-type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"Smoke\",\"inviteCode\":\"$INVITE\"}")
[[ "$code" == "201" ]] || { echo "register failed: $code"; cat /tmp/pam-go-register.json; exit 1; }

code=$(curl -s -o /tmp/pam-go-login.json -w '%{http_code}' -X POST "$BASE/v1/sessions" \
  -H 'content-type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
[[ "$code" == "201" ]] || { echo "login failed: $code"; cat /tmp/pam-go-login.json; exit 1; }
TOKEN=$(json "d['accessToken']" < /tmp/pam-go-login.json)
REFRESH=$(json "d['refreshToken']" < /tmp/pam-go-login.json)

for purpose in GMAIL_READ AI_PROCESSING; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/consents" \
    -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
    -d "{\"purpose\":\"$purpose\",\"decision\":\"GRANTED\",\"noticeVersion\":\"v0.4-review\"}")
  [[ "$code" == "201" ]] || { echo "consent $purpose failed: $code"; exit 1; }
done

CATEGORY=$(curl -s "$BASE/v1/categories" -H "authorization: Bearer $TOKEN" \
  | python3 -c "import json,sys; print(next(c['id'] for c in json.load(sys.stdin)['categories'] if c['code']=='food'))")
code=$(curl -s -o /tmp/pam-go-tx.json -w '%{http_code}' -X POST "$BASE/v1/transactions" \
  -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d "{\"direction\":\"DEBIT\",\"amountMinor\":17500000,\"currency\":\"IDR\",\"eventAt\":\"2026-11-19T04:10:00Z\",\"categoryId\":\"$CATEGORY\",\"description\":\"Smoke\"}")
[[ "$code" == "201" ]] || { echo "transaction failed: $code"; cat /tmp/pam-go-tx.json; exit 1; }

SUMMARY=$(curl -s "$BASE/v1/summaries/2026-11" -H "authorization: Bearer $TOKEN")
OUTFLOW=$(python3 -c "import json,sys; d=json.loads(sys.argv[1]); print(d['grossOutflowMinor'])" "$SUMMARY")
[[ "$OUTFLOW" == "17500000" ]] || { echo "summary outflow mismatch: $OUTFLOW"; exit 1; }

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/sessions/refresh" \
  -H 'content-type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}")
[[ "$code" == "200" ]] || { echo "refresh failed: $code"; exit 1; }

echo "GO SMOKE PASS: register/login/consent/transaction/summary/refresh"
