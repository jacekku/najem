#!/usr/bin/env bash
#
# Seeds a demo agency so the prototype never renders against an empty database.
#
# Everything here goes in through the public API, so it exercises the same paths a
# user does and cannot drift from them: if an endpoint changes shape this script
# breaks, which is the point. Nothing is inserted into a table directly.
#
#   ./tools/seed-demo.sh                    # against localhost:8080
#   BASE=http://localhost:9000 ./tools/seed-demo.sh
#
# Re-running adds another agency rather than editing the last one. To start over,
# drop and recreate the database (see RUNNING.md) and restart the app.

set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
BANK="${BANK:-http://localhost:8081}"
WORKSPACE="${WORKSPACE:-}"

# Registered as this agency's own account below. There is no deployment-wide IBAN
# any more, so the account the app fetches from is the one seeded here by name.
IBAN="${IBAN:-PL61109010140000071219812874}"

command -v jq >/dev/null || { echo "seed-demo needs jq"; exit 1; }

# The reads are what the prototype renders, and they are fed asynchronously by the
# outbox dispatcher. A board queried in the same breath as the write that feeds it
# legitimately returns the old answer, so the summary at the end waits rather than
# reporting an empty screen as a failure.
SETTLE=2

api() {
  local method=$1 path=$2 body=${3:-}
  if [ -n "$body" ]; then
    curl -sS -X "$method" "$BASE$path" \
      -H "Content-Type: application/json" -H "X-Workspace-Id: $WORKSPACE" -d "$body"
  else
    curl -sS -X "$method" "$BASE$path" -H "X-Workspace-Id: $WORKSPACE"
  fi
}

if [ -z "$WORKSPACE" ]; then
  # Creating it through /api/um/workspaces makes the caller its first ADMIN, which is
  # the only legitimate way an agency comes into being (invite-only, human ruling).
  # It needs najem.bootstrap.operator-subject set on the running app.
  WORKSPACE=$(curl -sS -X POST "$BASE/api/um/workspaces" -H "Content-Type: application/json" \
    -d '{"name":"Nieruchomości Śródmieście"}' | jq -r '.workspaceId // empty')
  [ -n "$WORKSPACE" ] || {
    echo "Could not create an agency. Start the app with --najem.bootstrap.operator-subject=<uuid>,"
    echo "or pass WORKSPACE=<existing-id> to seed into one that already exists."
    exit 1
  }
fi
echo "agency $WORKSPACE"

# lawfulBasis is required and is a RODO term, not a formality: "contract" is the
# basis for holding a tenant's details, and the retention rules downstream key on it.
contact() { # given surname email -> contactId
  api POST /api/contacts "$(jq -nc --arg g "$1" --arg s "$2" --arg e "$3" \
    '{givenName:$g,surname:$s,email:$e,phone:"+48 500 000 000",lawfulBasis:"contract"}')" \
    | jq -r '.contactId'
}

property() { # address -> propertyId
  api POST /api/pm/properties "$(jq -nc --arg a "$1" '{address:$a}')" | jq -r '.propertyId'
}

unit() { # propertyId name rent -> unitId
  api POST "/api/pm/properties/$1/units" "$(jq -nc --arg n "$2" --argjson r "$3" \
    '{name:$n,baseRent:$r}')" | jq -r '.unitId'
}

# A tenancy is reserved, then activated: two steps on purpose, because a signed
# agreement that has not started yet is a real state the unit board distinguishes.
tenancy() { # unitId contactId rent start ref -> tenancyId
  local id
  id=$(api POST /api/pm/tenancies "$(jq -nc \
    --arg u "$1" --arg c "$2" --argjson rent "$3" --arg start "$4" --arg ref "$5" \
    '{unitId:$u,tenantContactIds:[$c],startDate:$start,legalForm:"zwykly",
      monthlyTotal:$rent,rent:$rent,rentDay:10,depositAmount:($rent*2),paymentReference:$ref}')" \
    | jq -r '.tenancyId')
  api POST "/api/pm/tenancies/$id/activate" "$(jq -nc --arg d "$4" '{activatedOn:$d}')" >/dev/null
  echo "$id"
}

echo "contacts…"
KOWALSKI=$(contact "Anna"   "Kowalska"    "a.kowalska@example.com")
NOWAK=$(contact    "Piotr"  "Nowak"       "p.nowak@example.com")
WISNIEWSKI=$(contact "Marta" "Wiśniewska" "m.wisniewska@example.com")
ZIELINSKI=$(contact "Tomasz" "Zieliński"  "t.zielinski@example.com")

echo "properties and units…"
P1=$(property "ul. Marszałkowska 12, 00-026 Warszawa")
P2=$(property "ul. Hoża 45/7, 00-681 Warszawa")
P3=$(property "al. Jerozolimskie 101, 02-011 Warszawa")

P1M1=$(unit "$P1" "m. 1" 3200); P1M2=$(unit "$P1" "m. 2" 2850); P1M3=$(unit "$P1" "m. 3" 4100)
P2M1=$(unit "$P2" "m. 1" 2600); P2M2=$(unit "$P2" "m. 2" 3050)
P3M1=$(unit "$P3" "lokal A" 6800)
# Deliberately left vacant, so the board has something other than occupied rows to show.
_P3M2=$(unit "$P3" "lokal B" 5400)

echo "tenancies…"
# The payment reference is what reconciliation matches a bank line against, so these
# are the values the FakeBank scenarios and the bank screen have to agree on.
T1=$(tenancy "$P1M1" "$KOWALSKI"   3200 "2026-01-01" "NAJEM/M12/2026")
T2=$(tenancy "$P1M3" "$NOWAK"      4100 "2026-03-01" "NAJEM/M12/2026-3")
T3=$(tenancy "$P2M1" "$WISNIEWSKI" 2600 "2025-11-01" "NAJEM/H45/2025")
T4=$(tenancy "$P3M1" "$ZIELINSKI"  6800 "2026-05-01" "NAJEM/AJ101/2026")

sleep "$SETTLE"

# Payments, through the real path: FakeBank is seeded with transfers quoting the
# tenancy references above, the app fetches them, and reconciliation matches them.
# Nothing is inserted as "paid" -- the arrears colours below are earned by the
# ladder actually matching a bank line, which is what makes the bank screen worth
# looking at. A demo where the colours were set directly would show the same
# picture and prove nothing.
if curl -sSf -o /dev/null "$BANK/api/accounts/$IBAN/transactions" 2>/dev/null; then
  echo "bank transfers…"
  # Each agency names its own account, and there is deliberately no fallback: an
  # agency with none simply cannot reconcile. Skipping this leaves ingestion
  # failing with NoBankAccountRegisteredException, which is the correct refusal.
  api PUT /api/acc/workspace-account "$(jq -nc --arg i "$IBAN" '{iban:$i}')" >/dev/null
  scenario() { # scenario reference amount anchor
    curl -sS -X POST "$BANK/api/scenarios" -H "Content-Type: application/json" \
      -d "$(jq -nc --arg n "$1" --arg i "$IBAN" --arg r "$2" --argjson a "$3" --arg d "$4" \
        '{name:$n,iban:$i,reference:$r,amount:$a,anchorDate:$d}')" >/dev/null
  }
  # Money that has already arrived, so book it in the past. A fixed future date made
  # the suspense screen report daysWaiting: -6 — an item that has been waiting minus
  # six days reads as a broken screen, and it would have been the reviewer's time
  # spent finding out it was the seed data. Nine days back puts the two unresolved
  # items past a week, which is where they start to look like they need a person.
  BOOKED=$(date -v-9d +%Y-%m-%d 2>/dev/null || date -d '9 days ago' +%Y-%m-%d)

  # One of each interesting kind, so every tier of the ladder has something to show
  # and the arrears board has more than one colour.
  scenario on-time         "NAJEM/M12/2026"    3200 "$BOOKED"   # matches cleanly
  scenario partial         "NAJEM/H45/2025"    2600 "$BOOKED"   # underpaid, stays amber
  scenario wrong-reference "NAJEM/AJ101/2026"  6800 "$BOOKED"   # needs a human
  scenario no-reference    "NAJEM/M12/2026-3"  4100 "$BOOKED"   # lands in suspense

  api POST /api/acc/ingest/fetch >/dev/null
  sleep "$SETTLE"

  # Confirm exactly one suggestion, and leave the rest.
  #
  # The ladder proposes; a person decides -- so nothing is settled until somebody
  # confirms it, and an all-red arrears board is the honest picture of four
  # unconfirmed payments. Confirming one gives the report a second colour, and
  # leaving the others means the bank screen still has real work on it to click
  # through. Auto-confirming everything would produce a tidier demo that misrepresents
  # the one thing this module is actually about.
  FIRST=$(api GET /api/acc/suggestions | jq -r '.[0].paymentId // empty')
  [ -n "$FIRST" ] && api POST "/api/acc/payments/$FIRST/confirm" >/dev/null
  sleep "$SETTLE"
else
  echo "bank transfers… SKIPPED (FakeBank not reachable at $BANK)"
  echo "  start it with ./gradlew :apps:fakebank:bootRun -- the arrears board will"
  echo "  show every tenancy in arrears until payments arrive, which is correct but dull."
fi

cat <<SUMMARY

agency      $WORKSPACE
properties  3 (7 units, 4 let, 3 vacant)
tenancies   $T1
            $T2
            $T3
            $T4

Four transfers were seeded in FakeBank and ingested through the real
reconciliation path -- one clean match, one underpayment, one with a mangled
reference and one with none at all. So the bank screen has a case at each tier
of the ladder, including the two that no rule can settle and a person has to.

  open $BASE/
  curl -s -H "X-Workspace-Id: $WORKSPACE" "$BASE/api/reporting/units?propertyId=$P1" | jq
SUMMARY
