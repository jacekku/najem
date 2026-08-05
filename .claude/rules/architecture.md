# Architecture rules

The shape this codebase is being moved toward, and the rules that decide whether a change moves
with it or against it. The concrete dependency graph as it stands today is in
[refactoring.md](refactoring.md) under rule 4.

Written 2026-08-05, from the `AllocationService` refactoring.

## The layers

```
  [ rest | cli | scheduled | inbound event handler ]     driving adapters
                        │ knows about, drives
                        ▼
                    service                              application layer
                        │ uses
        ┌───────────────┼────────────────────────┐
        ▼               ▼                        ▼
     domain      driven ports              other services
                 (repositories,
                  bank feeds, …)
                        ▲ implements
                        │
              driven adapters (Postgres, MT940, …)
```

**Allowed:** driving adapter → service; service → domain; service → driven port; service → service;
driven adapter → port, service, domain.

**Forbidden:** domain → anything outside the JDK; port → adapter; service → adapter.

## Rules

**A1. A driving adapter knows about the service. The service does not know it exists.**
REST, CLI, schedulers and inbound event handlers all call in. Nothing calls back out to them. The
same use case must be reachable from a second entry point without the service changing.

**A2. A service may use the domain, driven ports, and other services.**
There is deliberately no domain-service / application-service distinction. Arguing about which
category a class belongs to produced no better code than deciding what it may depend on, and the
distinction forced a false choice about which package the repository interfaces live in. A service
is a service; what it may reach for is what matters.

**A3. The domain drives nothing.**
Entities, value objects and events. No repositories, no clock, no transactions, no framework — it
imports nothing outside the JDK, and that is checkable. `Invoice`, `Payment`, `PaymentStatus`,
`Component`, `ChargePosted` are the domain; they hold rules and are reached for, and reach for
nothing.

**A4. Repositories and other outgoing collaborators drive nothing.**
They are called. A repository that calls a service has become a service.

**A5. Every driven port is an interface with a real implementation and an in-memory double.**
`PaymentRepository` → `PostgresPaymentRepository` + `InMemoryPaymentRepository`. The in-memory one
is not optional: it is what makes the rules testable in milliseconds, and writing it is what
reveals whether the port is expressed in the domain's terms or the database's.

*This rule describes the target, not the present.* See "Migration front" below. Code that predates
its port is unmigrated, not broken — but new work does not add to the pile.

**A6. Service-to-service dependencies point one way. The graph is a DAG.**
`AccountingService → AllocationService` is fine because the arrow never comes back.

**A cycle between services is a red flag for the domain model, not a wiring problem.**
Spring will happily resolve one and the application will start, which is precisely the danger — the
tooling hides it. When two services need each other it almost always means one of:

- *A concept has not been named.* The thing they both need is real and has no class yet. Extracting
  it breaks the cycle and usually improves both sides.
- *A side effect is in the wrong place.* One service is doing something that belongs to a caller
  above them both. This is exactly where `AccountingService` came from: `AllocationService` reached
  for the arrears board, which is a consequence of settling charges rather than part of deciding
  where money lands. Lifting the refresh into a caller removed the reach.
- *A boundary is drawn in the wrong place.* The two are one concept that has been split, or one
  concept that should be two and has been split down the wrong seam.

So: do not break a cycle by injecting a proxy, an `@Lazy`, a setter, or an event purely to defer
the reference. Those hide the modelling question. Find the unnamed concept or the misplaced side
effect.

## Migration front

Rule A5 is the destination. As of 2026-08-05 the accounting module is partway there:

- **Behind ports:** the `allocate` path only — `PaymentRepository`, `InvoiceRepository`,
  `AccountingRepository`.
- **Not yet:** `org.springframework.jdbc.core.JdbcTemplate` is imported **11 times** in
  `pl.najem.acc.application`. `BoardService`, `LedgerService`, `CorrectionService`,
  `IngestionService`, `SuspenseService`, `DepositService` and others are each a service and their
  own repository at once.

Each of those imports is a port that has not been named yet. `BoardService` holding a
`JdbcTemplate` is not a violation of A5 — it is a class the extraction has not reached. The
distinction is practical: a violation blocks a merge, a migration front is tracked and worked
down. Treating them alike is how a rule stops being believed.

When the last one is extracted, delete this section.
