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
  --najem.bank.iban=PL61109010140000071219812874"
```

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

`GET /workspace` returns **403** until your subject belongs to a workspace.
That is the seam working, not a bug: an ambiguous or absent workspace resolves
to denied rather than to a guess.

### A round trip through the API

Every write needs `X-Workspace-Id`. Any UUID acts as a workspace for now; the
check that the *caller* is entitled to it is the next piece of work.

```sh
W=00000000-0000-4000-8000-000000000001

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
- **Screens.** Only the scaffold, the home page and the workspace page render.
  Reconciliation, arrears and the unit board are API-only so far.
- **Reads still fall back** to a default workspace in a few places where writes
  no longer do.
