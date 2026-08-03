# NAJEM

Property management for the Polish long-term rental market — modular monolith (Java 21, Spring Boot 3.3, event-sourced on Postgres) plus a separate FakeBank app for testing bank integration flows.

## Documentation

- Domain models (event-storming output): `docs/event-storming/property-management-domain-model.md`, `docs/event-storming/accounting-domain-model.md`
- Research (law, ledger design, bank integration, deposits): `docs/event-storming/research/`
- Implementation roadmap & plans: `docs/superpowers/plans/`

## Prerequisites

- JDK 21 (`brew install openjdk@21`) — export `JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"`
- Docker (colima works; tests need `DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"`)

## Run

```sh
docker compose up -d                      # Postgres on :5432
./gradlew :apps:fakebank:bootRun          # FakeBank on :8081
./gradlew :apps:najem-app:bootRun         # NAJEM on :8080
```

Walk the skeleton by hand:

```sh
P=$(curl -s -X POST localhost:8080/api/pm/properties -H 'Content-Type: application/json' \
  -d '{"address":"Testowa 1, Kraków"}' | jq -r .propertyId)
U=$(curl -s -X POST localhost:8080/api/pm/properties/$P/units -H 'Content-Type: application/json' \
  -d '{"name":"M1","baseRent":2500}' | jq -r .unitId)
T=$(curl -s -X POST localhost:8080/api/pm/tenancies -H 'Content-Type: application/json' \
  -d "{\"unitId\":\"$U\",\"startDate\":\"2026-09-01\",\"monthlyRent\":2500,\"paymentReference\":\"NAJEM/M1/2026\"}" | jq -r .tenancyId)
curl -s -X POST localhost:8080/api/pm/tenancies/$T/activate -H 'Content-Type: application/json' \
  -d '{"activatedOn":"2026-09-01"}'
# outbox dispatches within ~500ms; board shows "awaiting"
curl -s -X POST localhost:8081/api/accounts/PL61109010140000071219812874/transactions \
  -H 'Content-Type: application/json' \
  -d "{\"id\":\"tx-1\",\"amount\":2500,\"title\":\"NAJEM/M1/2026\",\"bookingDate\":\"$(date +%F)\"}"
curl -s -X POST localhost:8080/api/acc/ingest/fetch
PAY=$(curl -s localhost:8080/api/acc/suggestions | jq -r '.[0].paymentId')
curl -s -X POST localhost:8080/api/acc/payments/$PAY/confirm
curl -s localhost:8080/api/acc/board     # -> "green"
```

## Test

```sh
./gradlew build          # all modules, includes Testcontainers + e2e walking skeleton
./gradlew :e2e:test      # just the end-to-end flow
```

## Architecture (short)

- `contracts/` — integration events + handler SPI (frozen; changes need coordinator sign-off)
- `platform/eventstore/` — jsonb event store, optimistic concurrency, transactional outbox; delivery = direct in-process handler calls (no queues)
- `modules/` — one Gradle module per bounded context; modules never depend on each other
- `apps/najem-app` — composition root; `apps/fakebank` — bank simulator implementing the statement port
- `e2e/` — end-to-end scenarios, grown one per feature
