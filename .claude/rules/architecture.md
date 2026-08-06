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

*Checked 2026-08-06, and it holds:* grepping every module's `domain` package for a non-JDK import
returns nothing. Zero exceptions in four modules, which is worth knowing before anyone spends the
first one.

*One is being asked for.* A shared `DomainEvent` marker in `platform:eventstore` would let
`EventStore.append` take `List<? extends DomainEvent>` instead of `List<?>` — the only version of
that signature which actually refuses a `String`. It would also be the first A3 exception, and the
argument for it is that an empty marker interface drives nothing, so it breaks A3's letter and not
its spirit. Left as a TODO on `EventStore.append` rather than taken, because a rule with zero
exceptions and a rule with one are different rules, and which one this is should be decided
deliberately rather than as a side effect of a typing cleanup.

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

**A7. A name carries the concept, not the screen — and a derived store says so.**
Two halves of the same rule, both earned on `BoardService`.

*Name the concept.* `BoardService` names the thing a manager looks at; `ArrearsBoardService` names
what it decides. A generic head noun — `Board`, `Status`, `Standing`, `Manager` — is a name that
has stopped short. The test: put the class name next to its sibling and see whether the pair still
tells you which is which. `Standing` and `Payment` do not; `ArrearsStanding` and `Payment` do.

*Say when a store is derived.* A table that can be dropped and rebuilt from another table is a
projection, and the port onto it is `…Projection`, not `…Repository`. `acc_tenancy_status` holds
nothing that is not already in `acc_charge`. Naming it a repository invites the one thing that must
never happen: a service reading the colour to decide something, at which point the board stops being
derived and becomes an opinion — which is the failure `BoardService`'s own javadoc records fighting
its way out of. The name is the guardrail. `Repository` is for the record; `Projection` is for the
view of it, and only the record may be read to make a decision.

Prefer `Projection` over `ReadModelRepository`. A projection is denormalized and rebuildable by
definition, so the longer name adds a word and drops the more useful signal — that the suffix
`Repository` does not apply here at all.

## A7, and the third suffix

`Repository` for the record, `Projection` for a derived store — and `Query` for a read that is
neither. `SuggestionQuery` joins acc_suggestion to the payment and charge it names; there is no
denormalized table to rebuild, so `Projection` would be a claim about storage that is not true, and
`Repository` is taken by the record itself. Reach for it only when both of the others are wrong.

Two ports over one derived table is not duplication. `ArrearsStandingProjection` writes
acc_tenancy_status and is what services hold; `ArrearsBoardProjection` reads it and is what a screen
holds. One port carrying both would put a read of the colour within reach of every service that
refreshes it, which is the failure A7 exists to prevent.

## Reporting is a named exception to A5, not an open front

`pl.najem.reporting.application` imports `JdbcTemplate` **10 times** and that is the intended end
state. Do not put it on a list of things to work down.

Rule 1 says check the premise, and the premise "every module should end with zero adapter imports
in its application package" does not survive contact with this one. Reporting has **no `domain`
package at all** — no aggregates, no commands, no events of its own. Every table it owns is derived
from the shared event store, and the classes in its application package are already named the way
A7 asks: `PropertyProjection`, `UnitBoardQuery`, `TenancyTimelineProjection`.

**The reason A5 does not pay here is that the logic is the SQL.** In accounting, PM and
usermanagement the ports moved *decisions* off `JdbcTemplate` so rules could be tested in
milliseconds; the settlement order, the last-admin rule and the invitation lifecycle are all Java.
`UnitBoardQuery` has no such decision. Its content is one predicate —
`not p.annulled and (not p.released or p.ended_on is not null)` — and that predicate exists nowhere
but the string. An in-memory double for it would restate the predicate in Java, look identical to
the Postgres assertion, and be incapable of catching a regression in the real one. That is exactly
the `InMemoryInvoiceRepository.fullySettled()` incident that earned refactoring rules 13 and 14, and
extracting these ports would manufacture it twelve times over.

So the seam was drawn where a decision actually lives:

- **`ProjectionRunner`** holds the only branching in the module that is not SQL — the batch loop,
  the fetched-versus-applied distinction, per-projection positions, rebuild-then-drain. It now
  reaches `EventFeed` and `CheckpointStore` as ports and imports no `JdbcTemplate`.
- **`EventFeed`** is a port because its allowlist is *policy* (rule 10) and its `where` clause is
  mechanism. `ALLOWED_STREAMS` lives on the interface; the SQL lives in `PostgresEventFeed`.
- **`CheckpointStore`** is the one `Repository` in the module. Every other table here can be dropped
  and replayed; `reporting_checkpoint` holds the position that says what still needs replaying.
- **Everything else stays on `JdbcTemplate`**, in `application`, by decision.

*The check that replaces the grep.* `application` importing `JdbcTemplate` proves nothing here, so
the mechanical check for this module is the other direction: `pl.najem.reporting.application` must
import `pl.najem.reporting.adapter` **0 times**. Verified 2026-08-06, and it holds.

## Both migration fronts are closed

As of 2026-08-06, `org.springframework.jdbc.core.JdbcTemplate` is imported **0 times** in
`pl.najem.acc.application`, down from 11, and **0 times** in `pl.najem.pm.application`, down from 7.
Every service in both modules reaches its store through a port with a Postgres adapter and an
in-memory double, and the mechanical check — grepping the application package for adapter imports —
returns nothing in either.

PM's front closed differently from accounting's, because PM really is event-sourced (rule 1: check
the premise). Its aggregates rebuild from `store.load(id, type).events()` and its tables are written
after the append, so almost every port there is a `Projection` and the record is the stream. That is
also what retired `WorkspaceGuard`: it asked a projection who owned a subject while the service was
rebuilding the aggregate holding the same fact one line later. One question had two answers, and the
one it trusted was the derived one.

*This section replaces the tracked front and should stay until it stops being news.* What it was for
is worth remembering: a violation blocks a merge, a migration front is tracked and worked down, and
treating them alike is how a rule stops being believed.

## `application` importing `pl.najem.eventstore` is not a front

This file used to end by naming that import as "the next front worth naming". It was checked on
2026-08-06 and it is not one, so the sentence is gone rather than left to be worked down by somebody
who trusted it.

What is actually there: `EventStore` is an **interface**, `JdbcEventStore` is its adapter in
`platform:eventstore`, and all 18 application-layer imports across the modules are of the interface.
No application class imports `JdbcEventStore` or `OutboxDispatcher`. `platform:eventstore` depends
on nothing but `contracts`, so the arrow points the way A2 requires — `service → driven port` — and
the port simply lives in a shared platform module instead of being redeclared in each application
package. A5's in-memory double exists on both sides: `RecordingEventStore` and `InMemoryEventStore`.

The two things that could still be argued, neither a violation:

- The four `…EventTypes` classes at each module's root hold Jackson registration, which is closer to
  an adapter concern than to a module's public surface. A naming judgement.
- Each module could declare its own `EventStore` port in its own terms, with the platform adapter
  implementing all of them. That is the purist reading of A5 and it buys four identical interfaces
  for one genuinely shared capability. Not worth it unless two modules ever need different terms.

*This is here because the alternative is worse.* A rules file that names a front which does not
exist costs somebody a day proving it, and spends the credibility that makes the real rules bite —
which is the failure the section above already warns about. Rule 1 applies to this file too: check
the premise before acting on it, including a premise this file wrote down itself.
