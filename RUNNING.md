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

## 4. Sign-in (Keycloak)

NAJEM does not have a password. Keycloak is the identity provider, the sign-in
page is a link rather than a form, and the only thing NAJEM ever learns about a
person is their subject id. Prepare the realm and a person to be:

```sh
./tools/seed-keycloak.sh          # creates the realm, client, audience mapper and user demo/demo
```

It prints a subject id. That id is who the application will let create the first
agency, and the same id has to be given to the seeding script below — an agency
created by one account and logged into as another shows an empty screen.

## 5. The application (port 8080)

```sh
KCU=http://localhost:8180/realms/najem
./gradlew :apps:najem-app:bootRun --args="\
  --spring.security.oauth2.client.registration.keycloak.client-id=najem-app \
  --spring.security.oauth2.client.registration.keycloak.client-authentication-method=none \
  --spring.security.oauth2.client.registration.keycloak.scope=openid,profile,email \
  --spring.security.oauth2.client.provider.keycloak.issuer-uri=$KCU \
  --spring.security.oauth2.resourceserver.jwt.issuer-uri=$KCU \
  --najem.security.audience=najem-app \
  --najem.bootstrap.operator-subject=<the subject seed-keycloak.sh printed> \
  --najem.bank.fake.enabled=true \
  --najem.bank.base-url=http://localhost:8081"
```

`client-authentication-method=none` says the client is public, which is what
makes Spring send a PKCE challenge. Keycloak is configured to require one, so
without this flag every sign-in fails at the authorization endpoint.

There is no `--najem.bank.iban`. Each agency registers its own account
(`PUT /api/acc/workspace-account`), because a deployment-wide IBAN answered
"whose money is this?" with a setting rather than with the data.

### Running without any sign-in

For a click-through with no Keycloak at all, replace every `spring.security.*`
flag with `--najem.security.permit-all=true`. Omit both and the app refuses to
start rather than silently serving every endpoint unauthenticated.

The application also refuses to start if an issuer is configured without
`najem.security.audience`, or without a client registration — a deployment that
can validate tokens but cannot sign anybody in serves screens that only ever
answer 403.

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

## 6. Poke at it

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
KC_USER=demo KC_PASS=demo ./tools/seed-demo.sh        # signs in to seed
```

With `permit-all` instead, drop `KC_USER` and pass
`NAJEM_OPERATOR_SUBJECT=<the same uuid you started the app with>`. Everything
the script does goes through the public API, so with sign-in enabled it needs a
token like any other client.

It prints the agency id, which is worth keeping for reading the database — but
you do not send it anywhere. **The API takes no workspace.** It derives one from
whoever you are, so the script must be run once against a fresh database: a
second agency under the same operator makes every call ambiguous and the
application refuses to guess.

To start over, **drop and recreate the database** (below) rather than emptying
`events` — the projections outlive a truncated event table, and the stale rows
collide with the new agency in ways that surface several steps later. FakeBank
keeps its scenarios in memory with no reset, so restart it too.

Signing in grants nothing by itself. A valid Keycloak account with no NAJEM
invitation is refused — in those words, rather than "access denied", because it
is an invitation problem and not a permissions one. Somebody who has an account
but belongs to no agency gets a screen explaining that instead.

### A round trip through the API

No call names a workspace. Every endpoint acts in the agency the caller belongs
to, read and write alike, and there is no field or header for saying otherwise —
so acting in somebody else's agency is not refused, it is unsayable.

`X-Workspace-Id` used to carry it. It is gone: the check that made it safe lived
in one interceptor, and every endpoint added afterwards had to remember to be
covered by it. What remains is a resolver the tests switch on with
`najem.test.workspace-header=true`, which nothing packaged sets.

Belong to two agencies and a bearer-token call answers **409 `choose-agency`**
rather than picking one — the browser has a chooser for this, an API client does
not, and guessing would decide whose books a write lands in. Belong to none and
it answers **403 `no-agency`**.

```sh
P=$(curl -s -X POST http://localhost:8080/api/pm/properties \
  -H "Content-Type: application/json" $AUTH \
  -d '{"address":"ul. Marszalkowska 12, Warszawa"}' | jq -r .propertyId)

U=$(curl -s -X POST http://localhost:8080/api/pm/properties/$P/units \
  -H "Content-Type: application/json" $AUTH \
  -d '{"name":"m. 3","baseRent":3200}' | jq -r .unitId)

# The board is a projection fed asynchronously — allow a moment after a write.
curl -s $AUTH "http://localhost:8080/api/reporting/units?propertyId=$P" | jq
```

`$AUTH` is `-H "Authorization: Bearer <token>"` with sign-in on, and empty under
`permit-all`, where every call acts as the configured operator.

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

- **Roles.** Membership of the agency a caller names is checked, but *which*
  role they hold is not consulted by the REST APIs — any member may do anything
  their agency can do.
- **Invitations by email.** An account is created by accepting an invitation,
  and issuing one still means calling the API rather than clicking a screen.
- **A second agency cannot register a bank account** if another already holds
  that IBAN. The refusal is correct — an account belongs to one agency — but it
  arrives as a 500 carrying a raw Postgres constraint error, and the symptom
  then surfaces three steps later as "this workspace has no bank account".
- **`:e2e:test` is red**, and not for a reason in this file: `e2e` puts both
  applications on one classpath and they both map `GET /`.

`marketState` is not an occupancy state — it says how a unit is being marketed,
and a let unit is legitimately still `inventory`. Whether a unit is let is
`currentTenancyId`. Two questions, two fields; don't merge them.
