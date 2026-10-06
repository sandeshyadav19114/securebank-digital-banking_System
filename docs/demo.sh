#!/usr/bin/env bash
# End-to-end demo against the docker-compose stack (dev profile prints OTPs in the app log).
#   docker compose up --build -d   &&   ./docs/demo.sh
# Requires: curl, jq, docker
set -euo pipefail
BASE=${BASE:-http://localhost:8080/api/v1}
for c in curl jq docker; do command -v "$c" >/dev/null || { echo "missing: $c"; exit 1; }; done

call() { # METHOD TOKEN PATH [BODY] [extra curl args...]
  local method="$1" token="$2" path="$3" body="${4:-}"; shift 4 || shift $#
  local hdr=(); [ -n "$token" ] && hdr=(-H "Authorization: Bearer $token")
  if [ -n "$body" ]; then
    curl -s -X "$method" "$BASE$path" -H 'Content-Type: application/json' ${hdr[@]+"${hdr[@]}"} "$@" -d "$body"
  else
    curl -s -X "$method" "$BASE$path" ${hdr[@]+"${hdr[@]}"} "$@"
  fi
}

login() { # email password -> prints JWT
  call POST "" /auth/login "{\"email\":\"$1\",\"password\":\"$2\"}" >/dev/null
  sleep 1
  local otp
  otp=$(docker compose logs --no-log-prefix --tail 300 app | grep "OTP for $1" | tail -1 | awk '{print $NF}')
  call POST "" /auth/verify-otp "{\"email\":\"$1\",\"otp\":\"$otp\"}" | jq -r .accessToken
}

RUN=$(date +%s); PASS='Str0ng!Passw0rd'
A="alice$RUN@example.com"; B="bob$RUN@example.com"
KYC='{"panNumber":"ABCDE1234F","aadhaarNumber":"123456789012","address":"12 MG Road, Jaipur"}'

echo "== register"; A_ID=$(call POST "" /auth/register "{\"fullName\":\"Alice\",\"email\":\"$A\",\"phone\":\"9876543210\",\"password\":\"$PASS\"}" | jq -r .id)
B_ID=$(call POST "" /auth/register "{\"fullName\":\"Bob\",\"email\":\"$B\",\"phone\":\"9876543211\",\"password\":\"$PASS\"}" | jq -r .id)
echo "alice=$A_ID bob=$B_ID"

echo "== login (password + OTP)"; ADMIN=$(login admin@securebank.local 'Admin@12345!'); TA=$(login "$A" "$PASS"); TB=$(login "$B" "$PASS")

echo "== KYC submit + admin approve"
call POST "$TA" /customers/me/kyc "$KYC" >/dev/null; call POST "$TB" /customers/me/kyc "$KYC" >/dev/null
call POST "$ADMIN" "/admin/kyc/$A_ID/approve" "" >/dev/null; call POST "$ADMIN" "/admin/kyc/$B_ID/approve" "" >/dev/null

echo "== open accounts"
ACC_A=$(call POST "$TA" /accounts '{"accountType":"SAVINGS","initialDeposit":50000}' | jq -r .accountNumber)
ACC_B=$(call POST "$TB" /accounts '{"accountType":"SAVINGS","initialDeposit":1000}' | jq -r .accountNumber)
echo "alice account=$ACC_A  bob account=$ACC_B"

echo "== alice adds bob as beneficiary"
call POST "$TA" /beneficiaries "{\"accountNumber\":\"$ACC_B\",\"name\":\"Bob\",\"nickname\":\"bobby\"}" | jq -c .

KEY="demo-$RUN-0001"
echo "== transfer 2500 (Idempotency-Key=$KEY)"
call POST "$TA" /transfers "{\"fromAccount\":\"$ACC_A\",\"toAccount\":\"$ACC_B\",\"amount\":2500,\"remarks\":\"rent\"}" -H "Idempotency-Key: $KEY" -H 'X-Client-Country: IN' | jq -c .
echo "== client retry with the SAME key -> replayed, NOT debited twice"
call POST "$TA" /transfers "{\"fromAccount\":\"$ACC_A\",\"toAccount\":\"$ACC_B\",\"amount\":2500,\"remarks\":\"rent\"}" -H "Idempotency-Key: $KEY" -i | grep -iE 'HTTP/|idempotent-replay' || true
echo "== balances"; call GET "$TA" "/accounts/$ACC_A/balance" "" | jq -c .; call GET "$TB" "/accounts/$ACC_B/balance" "" | jq -c .

echo "== transfer from a different country minutes later -> triggers the geo-inconsistency rule"
call POST "$TA" /transfers "{\"fromAccount\":\"$ACC_A\",\"toAccount\":\"$ACC_B\",\"amount\":600,\"remarks\":\"x\"}" -H "Idempotency-Key: demo-$RUN-0002" -H 'X-Client-Country: US' | jq -c .
sleep 3; call GET "$ADMIN" /admin/fraud-alerts "" | jq -c '.content[] | {rule: .ruleCode, severity, reason}'

echo "== statement"; TODAY=$(date -u +%F); call GET "$TA" "/accounts/$ACC_A/statement?from=$TODAY&to=$TODAY" "" | jq '{openingBalance, totalDebits, totalCredits, closingBalance}'
echo "== reconciliation"; call POST "$ADMIN" "/admin/reconciliation/run?date=$TODAY" "" | jq -c '{status, totalDebits, totalCredits, mismatchedAccounts}'
