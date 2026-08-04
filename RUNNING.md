# Running NAJEM locally

Verified against tag `v0.1-playable` on 2026-08-04: the app starts, serves the
home page, and a property → unit → board round trip works.

The application **refuses to start** unless you name a bank and a security
posture. That is deliberate (roadmap rule 7): a value that decides where money
comes from, or who may read the data, must never come from a packaged default.
Everything below is the local deployment naming its own.

## 1. Infrastructure

```sh
docker compose up -d          # postgres on 5432, keycloak on 8180
```

## 2. Build environment

`JAVA_HOME` must be Java 21. On this machine Docker is colima, so Testcontainers
needs to be told where the socket is (only required for the test suite):

```sh
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export DOCKER_HOST="unix:///Users/jaca/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
```

## 3. The fake bank (separate terminal, port 8081)

```sh
./gradlew :apps:fakebank:bootRun
```

Serves MT940 statements and a transactions API for reconciliation. Without it
the app still starts; it just reconciles against nothing.

## 4. The application (port 8080)

```sh
./gradlew :apps:najem-app:bootRun --args="\
  --najem.security.permit-all=true \
  --najem.bank.fake.enabled=true \
  --najem.bank.base-url=http://localhost:8081 \
  --najem.bootstrap.operator-subject=$(uuidgen | tr 'A-Z' 'a-z')"
```

There is no longer a `--najem.bank.iban`. Each agency registers its own account
(`PUT /api/acc/workspace-account`), because a deployment-wide IBAN answered
"whose money is this?" with a setting rather than with the data — one agency's
transfers would have been reconciled into another's books.

`--najem.bootstrap.operator-subject` is what lets you create the first agency:
until somebody belongs to one, nobody can make one. Any UUID will do for local
play, and the same one must be passed to `tools/seed-demo.sh` as
`NAJEM_OPERATOR_SUBJECT`.

`--najem.security.permit-all=true` is the explicit opt-out of authentication,
for local play only. Omit it and the app refuses to start rather than silently
serving every endpoint unauthenticated — which is what it used to do.

To run the *real* posture instead, drop that flag and supply
`--spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/najem`.
Every request then needs a token, so use the API sections below only with one.

### If Flyway refuses to start

```
Validate failed: Detected resolved migration not applied to database: 10
```

Your local database predates migrations that landed since. Migrations are
numbered per module and arrive out of order across agents, which is expected.
**Drop and recreate — do not set `outOfOrder=true`**, which would also silently
accept a genuinely missing migration later:

```sh
docker exec najem-postgres-1 psql -U najem -d postgres \
  -c "drop database if exists najem;" -c "create database najem owner najem;"
```

## 5. Poke at it

The home page is server-rendered Thymeleaf with htmx served from the app itself
(no CDN), and the copy is Polish:

```sh
open http://localhost:8080/
```

### Get some data in it first

An empty system shows empty screens. `tools/seed-demo.sh` builds a demo agency
entirely through the public API — three properties, seven units, four tenancies,
and four bank transfers ingested through the real reconciliation path, one at
each tier of the ladder: a clean match, an underpayment, a mangled reference and
one with no reference at all. Nothing writes a projection directly, so the
colours on the arrears board were computed by the code you are looking at.

```sh
NAJEM_OPERATOR_SUBJECT=<the same uuid you started the app with> ./tools/seed-demo.sh
```

It prints the agency id. **Put that in `X-Workspace-Id` for every call below.**

To start over, **drop and recreate the database** (below) rather than emptying
`events` — the projections outlive a truncated event table, and the stale rows
collide with the new agency in ways that surface several steps later. FakeBank
keeps its scenarios in memory with no reset, so restart it too.

`GET /workspace` returns **403** until your subject belongs to a workspace.
That is the seam working, not a bug: an ambiguous or absent workspace resolves
to denied rather than to a guess.

### A round trip through the API

Every call needs `X-Workspace-Id`, reads as well as writes. Any UUID is accepted
as a workspace; the check that the *caller* is entitled to it is the next piece
of work.

```sh
W=<the agency id seed-demo.sh printed>

P=$(curl -s -X POST http://localhost:8080/api/pm/properties \
  -H "Content-Type: application/json" -H "X-Workspace-Id: $W" \
  -d '{"address":"ul. Marszalkowska 12, Warszawa"}' | jq -r .propertyId)

U=$(curl -s -X POST http://localhost:8080/api/pm/properties/$P/units \
  -H "Content-Type: application/json" -H "X-Workspace-Id: $W" \
  -d '{"name":"m. 3","baseRent":3200}' | jq -r .unitId)

# The board is a projection fed asynchronously — allow a moment after a write.
curl -s -H "X-Workspace-Id: $W" \
  "http://localhost:8080/api/reporting/units?propertyId=$P" | jq
```

```json
[{ "unitId": "…", "name": "m. 3", "baseRent": 3200,
   "marketState": "inventory", "currentTenancyId": null, "nextTenancyId": null }]
```

**Reads are eventually consistent.** Writes go to an event store; projections
are updated by an in-process dispatcher shortly after. Query a board in the same
breath as the write that feeds it and you will legitimately see the old answer.
`GET /api/reporting/status` reports how far behind each projection is.

### Endpoint map

| Base | What lives there |
| --- | --- |
| `/api/pm` | properties, units, tenancies, repairs, compliance, attention |
| `/api/acc` | charges, payments, allocation, deposits, reconciliation |
| `/api/contacts` | contacts, interests, retention holds, erasure |
| `/api/reporting` | unit board, timelines, occupancy, projection status |
| `/api/um` | workspaces, invitations, members |

## What is not built yet

- **Authorization.** `X-Workspace-Id` says *which* workspace a request acts in.
  Nothing yet checks that the caller is entitled to that workspace — the seam
  exists (`WorkspaceCaller`) and the web layer uses it, but the REST APIs above
  still trust the header. Treat the API as unauthenticated.
- **Screens.** Being built now. Until they land, the boards below are API-only —
  the data is real and computed, it just has no page yet.
- **A second agency cannot register a bank account** if another already holds
  that IBAN. The refusal is correct — an account belongs to one agency — but it
  arrives as a 500 carrying a raw Postgres constraint error, and the symptom
  then surfaces three steps later as "this workspace has no bank account".
- **`marketState` reads `inventory` for let units too.** Use the presence of
  `currentTenancyId` to tell let from vacant until that vocabulary is settled.
