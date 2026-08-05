# Refactoring rules

Extracted from the `AllocationService` refactoring (branch
`refactor/allocation-service-repositories`, commits `85d91e0`, `8bea8a8`, `0485e50`, `2900017`).
Each rule names the incident that earned it, because the incident is the part that makes it
stick. Several record mistakes made during that work rather than principles applied to it.

## Verifying before acting

**1. Check the premise before acting on it.**
"This is event-sourced, so state should derive from the event stream" sounded right. Nine
`store.load` calls in the accounting module, every one used only for `.version()` on append. The
module is not event-sourced — the store is an append-only audit and outbox log beside state-based
persistence. Acting on the premise would have produced a wrong change. One grep settled it.

**2. Verify a Javadoc claim before preserving it.**
`AllocationService.refreshBoard` asserted "every writer of charges goes through here."
`LedgerService` had been calling `board.refresh` itself in three places for as long as it existed.
The comment documented an intention, not an invariant, and it nearly argued a correct change out
of existence. Claims in comments are evidence, not proof.

**3. Read a sketch for semantics, not for what fails to compile.**
A hand-written sketch contained `if (payment == null || payment.hasRemaining()) return ZERO;` —
inverted, returning early exactly when there was money to allocate. It would have compiled once
the missing methods existed. Compilation errors are the cheap half of reviewing someone's sketch.

## Extraction and layering

**4. An extraction is not done until the dependency points the right way.**
Ports were extracted, adapters written — and `@Autowired` was left on the constructor that
`new`-ed the Postgres adapters. Result: `pl.najem.acc.application` imported
`pl.najem.acc.adapter.persistence`, the exact inversion the ports existed to prevent, plus three
`@Repository` beans that were scanned and never injected.

*The check is mechanical:* grep the application package for imports of the adapter package. It
must return nothing.

### The graph, as it actually stands

Verified from imports in `modules/accounting/src/main/java/pl/najem/acc`, not from memory.

```
        ┌───────────────────────────────────────────────────────────────┐
        │  ADAPTER                                                      │
        │                                                               │
        │  driving (call in)          driven (called out to)            │
        │  ├── adapter/rest           └── adapter/persistence           │
        │  ├── adapter/bank               ├── PostgresPaymentRepository │
        │  ├── adapter/mt940              ├── PostgresInvoiceRepository │
        │  └── adapter/pm                 ├── PostgresAccountingRepo…   │
        │                                 └── PostgresArrearsStanding…  │
        │                                                               │
        │  testFixtures: PostgresAccounting (wiring, not in the jar)    │
        └───────────┬───────────────────────────────────┬───────────────┘
                    │ imports                           │ implements
                    ▼                                   ▲
        ┌───────────────────────────────────────────────┴───────────────┐
        │  APPLICATION                                                  │
        │                                                               │
        │  ports (interfaces owned here)                                │
        │  ├── PaymentRepository       ├── InvoiceRepository            │
        │  └── AccountingRepository                                     │
        │                                                               │
        │  services                                                     │
        │  ├── AccountingService ──► AllocationService ──► [ports]      │
        │  ├── ArrearsBoardService  LedgerService  CorrectionService     │
        │  └── SuspenseService  ReconciliationService  IngestionService │
        └───────────────────────────┬───────────────────────────────────┘
                                    │ imports (32 refs)
                                    ▼
        ┌───────────────────────────────────────────────────────────────┐
        │  DOMAIN                                                       │
        │  Invoice  Payment  PaymentStatus  Component  ArrearsColour    │
        │  ChargePosted  PaymentAllocated  …events                      │
        │                                                               │
        │  imports nothing outside the JDK                              │
        └───────────────────────────────────────────────────────────────┘
```

**Allowed:** `adapter → application`, `adapter → domain`, `application → domain`, and
`adapter implements application's ports` — the only arrow pointing up, and the point of the
arrangement.

**Forbidden:** `application → adapter`, `domain → anything`.

In one line: `AllocationService` names `PaymentRepository`; `PostgresPaymentRepository` names
`AllocationService`'s package. The concrete thing knows the abstract thing, never the reverse.

Two things the diagram hides, both true today:

- `application` imports `org.springframework.jdbc.core.JdbcTemplate` **10 times**. The layer is
  clean with respect to this module's adapter package but is still directly coupled to Spring JDBC
  everywhere except `allocate`. The ports covered one method, not the layer — this is the
  remaining work.
- `application` imports `pl.najem.eventstore` 6 times. That is the same inversion owned one level
  up: `EventStore` is a platform interface and `JdbcEventStore` its adapter.

Test-side: the in-memory fakes live in test sources under the *same package name*
`pl.najem.acc.application`. A separate source set, so no production edge — but they can reach
package-private application members if allowed to.

**5. Avoiding churn is not free.**
Constructor signatures were preserved to avoid touching callers, and the cost was rule 4. A small
blast radius is a nice property, not a correctness argument. When the two conflict, the diff loses.

**6. Infrastructure assembly belongs on the infrastructure side.**
"Convenience constructors for tests and callers outside the container" that `new` up concrete
adapters are a composition root hiding in the application layer. `PostgresAccounting` does the
same job in the direction that was wanted: adapter depends on application.

*And it belongs in the source set that uses it.* It lived in `src/main` first, which shipped a
helper no deployment calls in the jar every deployment carries — production has Spring, and Spring
does this assembly from the beans it scans. It is now `src/testFixtures`, which keeps it out of the
jar and still lends it to `modules:reporting`, whose tripwires drive accounting's real services and
need the same wiring. Right side of the boundary was only half the question; the other half is
whether it is production code at all.

**7. Count the callers before moving a side effect.**
`allocate` had three production callers, not one — `SuspenseService.allocateTo`,
`ReconciliationService.confirm`, `CorrectionService.amendAllocation`. Moving the arrears-board
refresh out without rerouting all three would have left managers reading stale colours after a
confirm: silent, and not the kind of thing anyone reports as a bug.

**8. Moving a side effect into a wrapper makes it unconditional.**
`allocate` returned early *before* refreshing the board. The orchestrator refreshes after it
returns, early or not, and `BoardService.refresh` is an upsert — so a call that used to be a no-op
now inserts an `acc_tenancy_status` row. Early-return paths are part of the behaviour being
preserved.

## Making rules hold

**9. Prefer structural invariants to procedural ones.**
`Payment.take` is package-private, so only an `Invoice` can move money and conservation holds for
every future caller. The previous `remaining.min(charge.owed())` held only for the one loop that
remembered to write it.

**10. Put policy in the layer that owns the decision.**
Settlement order stayed in `AllocationService` rather than moving into SQL: it is what the service
exists to decide and must not vary with the store. The `active and amount > allocated_amount`
filter did belong in SQL. Not everything in an extracted method moves to the same place.

**11. A domain enum over a stored column needs the column's whole vocabulary.**
`PaymentStatus` carries `SUGGESTED`, `REVERSED` and `NON_TENANT` although `Payment` never produces
them — `IngestionService`, `CorrectionService` and `SuspenseService` write those strings directly.
A half-enum throws when reading rows the module itself wrote.

**12. Pin wire values in a test when a type maps to stored data.**
Renaming a constant should stay free; renaming its wire name is a migration. The test is what
tells the two apart.

## Test doubles

**13. If a fake cannot fail, it is not a test.**
`InMemoryInvoiceRepository.fullySettled()` derived `allocatedAmount >= amount` from data it had
just written — true by construction. The SQL writes a *stored* `allocated` column via
`allocated = allocated_amount + ? >= amount`. The in-memory assertion looked identical to the
Postgres one and could not have caught any regression in that expression.

**14. A fake must model the mechanism, not the outcome.**
Same incident. Copying what the real thing ends up *saying* is how a fake starts lying; copy what
it *does*. The fake now stores the flag and computes it from the pre-update amount, as the
statement does.

**15. When an adapter's behaviour changes, test the adapter.**
Swapping `queryForObject` (throws `EmptyResultDataAccessException`) for `Optional` was the one
genuinely changed behaviour, and only the in-memory test covered it — which could prove that
`AllocationService` unwraps an empty `Optional` it was handed, never that the real query produces
one instead of throwing.

**16. Two test tiers, and the file says which one wins.**
A fast in-memory mirror (~4s) beside the Testcontainers suite (~2min), with a header stating that
when they disagree the database is right and the mirror is wrong. Worth writing in the file: the
next person to hit a disagreement needs to know which to trust before they start debugging.

## Reporting

**17. Enumerate behaviour changes explicitly — then have someone check the count.**
Two were declared. There were three. The third was invisible precisely because the list had
already been decided.

**18. Adversarial review catches what self-review structurally cannot.**
Two of the four real findings were self-inflicted, in code that had been read repeatedly. The
value was not extra eyes; it was eyes that had not already concluded the design was sound.
