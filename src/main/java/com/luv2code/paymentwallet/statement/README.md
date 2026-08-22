# The Statement Module

Phase 3. One screen — the transaction history page — and the four problems it creates.

```
GET /api/accounts/{accountNumber}/statement
        ?from=2026-08-01T00:00:00Z&to=2026-09-01T00:00:00Z
        &type=P2P&direction=DEBIT&minAmount=10&maxAmount=500
        &page=0&size=20&sort=createdAt,desc

GET /api/accounts/{accountNumber}/spend-by-tag?month=2026-08
```

A statement line is built from a **ledger entry**, not a transfer. That is deliberate: a
statement shows one row per movement *touching this account*, each with its own direction
and its own running `balance_after`. A transfer has two sides; only one of them is yours.

```
22 Aug   TRF430172EA   P2P   DEBIT   20.0000   balance 980.0000   to PW2976...   "lunch"
└─ ledger_entry ────────────────────┘          └─ ledger_entry ┘  └ transfer ┘   └ transfer ┘
```

---

## 1. Pagination

`Pageable` is a method parameter Spring Data understands; it turns into `LIMIT` / `OFFSET`.

```java
@GetMapping("/statement")
public Page<StatementLine> statement(
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
        Pageable pageable) { ... }
```

Spring binds `?page=`, `?size=` and `?sort=field,dir` automatically. `@PageableDefault`
supplies values when the client sends none — without it the default page size is 20 and
the order is undefined, and **an unordered page is a bug**: without `ORDER BY`, Postgres
may return rows in any order, so page 2 can repeat a row from page 1.

### `Page` vs `Slice`

| | Queries | Gives you |
|---|---|---|
| `Page<T>` | 2 — content + `count(*)` | `totalElements`, `totalPages` |
| `Slice<T>` | 1 — content, fetching `size + 1` | only `hasNext()` |

`Page` is what a statement screen with numbered pages needs. If you only need an infinite-
scroll "load more" button, `Slice` avoids the count query entirely — on a large table that
`count(*)` is often the more expensive half of the request.

Spring Data optimises this for you: **the count query is skipped when the result fits in a
single page.** That is why the measured numbers below differ by one.

### The scaling limit worth knowing

`OFFSET 100000 LIMIT 20` makes Postgres walk and discard 100,000 rows. Deep pagination is
O(offset), not O(1). The fix at scale is **keyset (cursor) pagination** — instead of an
offset, remember the last row you saw:

```sql
where account_id = ? and (created_at, id) < (?, ?)   -- the cursor
order by created_at desc, id desc
limit 20
```

That rides the index and is O(log n) at any depth, which is why real APIs hand you an
opaque `nextCursor` instead of `?page=5000`. This module uses offset pagination because a
personal statement is never thousands of pages deep — but the index it needs already
exists:

```sql
CREATE INDEX idx_ledger_account_created ON ledger_entry(account_id, created_at DESC);
```

---

## 2. Specifications — a WHERE clause built at runtime

Six optional filters means 2⁶ = 64 combinations. Derived query methods cannot express
that; you would be writing `findByAccountAndTypeAndCreatedAtBetweenAnd...` forever.

A `Specification<T>` is a lambda that returns a JPA `Predicate`. Each filter that is set
contributes one; each filter left null contributes nothing and never appears in the SQL:

```java
static Specification<LedgerEntry> matching(String accountNumber, StatementFilter filter) {
    return (root, query, cb) -> {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("account").get("accountNumber"), accountNumber));

        if (filter.from() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
        }
        if (filter.type() != null) {
            predicates.add(cb.equal(root.get("transfer").get("type"), filter.type()));
        }
        // ...
        return cb.and(predicates.toArray(new Predicate[0]));
    };
}
```

The three lambda parameters:

| Parameter | What it is |
|---|---|
| `root` | the FROM entity — `root.get("amount")` is a column, `root.get("transfer").get("type")` follows the association |
| `query` | the `CriteriaQuery` itself — `query.distinct(true)`, `query.orderBy(...)` |
| `cb` | `CriteriaBuilder` — the factory for `equal`, `like`, `greaterThan`, `and`, `or`, `isNull` … |

The repository opts in by extending `JpaSpecificationExecutor<LedgerEntry>`, which adds
`findAll(spec, pageable)`, `count(spec)`, `findOne(spec)`.

**`root.get("transfer").get("type")` is a path expression.** You never write a join
condition; Hibernate knows from `@JoinColumn` how `ledger_entry` reaches `transfer` and
emits the join itself. Getting a join condition wrong is a whole class of bug that simply
cannot happen here.

Specifications also compose:

```java
spec.and(other)      spec.or(other)      Specification.not(spec)
```

### Two traps

**Duplicate joins.** Calling `root.join("transfer")` twice produces the same join twice in
the SQL. Path expressions (`root.get(...)`) are reused automatically, so prefer them; if
you genuinely need an explicit `join`, hold it in a variable and reuse it.

**Strings are not type-safe.** `root.get("amont")` compiles fine and fails at runtime. The
fix is the JPA static metamodel (`hibernate-jpamodelgen` generates `LedgerEntry_.amount`),
which turns the typo into a compile error. Worth adding once a project has many
specifications.

---

## 3. `@EntityGraph` — the part that actually matters

### The problem

Rendering one statement line needs, per row:

- `amount`, `balanceAfter`, `direction` — from the ledger entry itself
- `reference`, `type`, `description` — from `transfer` (`@ManyToOne`, LAZY)
- the counterparty account number — from `transfer.sourceAccount` **or**
  `transfer.destAccount` (`@ManyToOne`, LAZY)

Every one of those associations is a lazy proxy. Touch it and it fires a `SELECT`.
Measured on 10 rows:

```
====== 10 statement lines ======
naive (lazy associations) : 12 queries
with @EntityGraph         :  3 queries
```

That is the **N+1 problem**: one query for the list, then N more as each row's proxy wakes
up. It scales with page size, so the endpoint gets *slower per row* the more rows you ask
for — the opposite of what a paginated endpoint should do.

### The fix

```java
@Override
@EntityGraph(attributePaths = {"transfer", "transfer.sourceAccount", "transfer.destAccount"})
Page<LedgerEntry> findAll(Specification<LedgerEntry> spec, Pageable pageable);
```

An entity graph is a **declarative fetch plan**: "whatever query you are about to run,
load these associations eagerly in the same statement." Hibernate rewrites the SQL to
include the joins and hydrates the associations, so no proxy is left to trigger.

Result — the query count no longer depends on page size:

```
statement queries: page size 5 (5 rows) = 3, page size 50 (30 rows) = 2
```

*(3 = account check + content + count; 2 = the same without the count, which Spring Data
skips when the result fits in one page.)*

### Nested paths and named graphs

Dot notation walks the graph — `"transfer.sourceAccount"` fetches the transfer *and* its
source account. Under the hood that is a subgraph; the annotation form is shorthand for:

```java
@NamedEntityGraph(name = "LedgerEntry.forStatement", attributeNodes = {
    @NamedAttributeNode(value = "transfer", subgraph = "transferWithAccounts")
}, subgraphs = @NamedSubgraph(name = "transferWithAccounts", attributeNodes = {
    @NamedAttributeNode("sourceAccount"), @NamedAttributeNode("destAccount")
}))
```

…declared on the entity and referenced with `@EntityGraph("LedgerEntry.forStatement")`.
Same effect; `attributePaths` is simply easier to read at the call site.

### `FETCH` vs `LOAD`

```java
@EntityGraph(attributePaths = {...}, type = EntityGraphType.FETCH)   // Spring Data default
```

| Type | Listed attributes | Everything else |
|---|---|---|
| `FETCH` | EAGER | forced LAZY, whatever the mapping says |
| `LOAD` | EAGER | keeps its mapped fetch type |

`FETCH` is the safer default: it means "this query fetches exactly this and nothing else",
so an accidental EAGER mapping elsewhere cannot quietly widen the query.

### Why not `join fetch`?

Both produce the same SQL. The difference is where the instruction lives:

| | `join fetch` | `@EntityGraph` |
|---|---|---|
| Written in | the JPQL string | an annotation |
| Works with `Specification` | ✗ — the WHERE is built dynamically, the string is not | ✓ |
| Works with derived queries (`findByX`) | ✗ | ✓ |
| Reusable across queries | ✗ | ✓ named graphs |

`TransferRepository.findAllByOwner` uses `join fetch` because the query is a fixed JPQL
string. The statement uses `@EntityGraph` because there is no query string to put a
`join fetch` into — the WHERE clause is assembled at runtime by the Specification. **That
is the concrete reason this module needed the annotation.**

### The limit: collections break pagination

Every path in that graph is a `@ManyToOne`. That matters:

```
HHH000104: firstResult/maxResults specified with collection fetch; applying in memory
```

Fetch-join a `@OneToMany` together with a `Pageable` and Hibernate **cannot** use
`LIMIT`/`OFFSET` — a parent with 3 children occupies 3 SQL rows, so the database's row
window is not the entity window. It therefore loads **every matching row** and paginates in
Java. On a large table that is an out-of-memory waiting to happen, announced only by a
`WARN` line most people never read.

Joining a to-one duplicates nothing, so `LIMIT` stays in Postgres. Rule of thumb:

> **to-one in the entity graph: always safe. Collection plus `Pageable`: never.**

If you do need a collection on a paginated result, the options are:

1. **Two queries** — page the ids first, then `where id in (:ids) join fetch children`.
2. **`@BatchSize(size = 25)`** on the collection — turns N queries into N/25 `IN` queries.
3. **`FetchMode.SUBSELECT`** — one extra query loading all children for the whole page.

All three keep the page itself paginated in the database.

---

## 4. Projections — return the shape, not the entity

`spend-by-tag` answers "where did my money go in August". Loading every August transfer and
summing in Java would materialise hundreds of entities to produce three numbers. Instead:

```sql
select new com.luv2code.paymentwallet.statement.TagSpendRow(tg.name, sum(t.amount), count(t))
from Transfer t
  join t.tags tg
where t.sourceAccount.accountNumber = :accountNumber
  and t.status = POSTED
  and t.createdAt >= :from and t.createdAt < :to
group by tg.name
order by sum(t.amount) desc
```

```
>>>>> spend by tag: [groceries total=45.0000 transfers=2,
                     rent      total=27.0000 transfers=1,
                     transport total=19.0000 transfers=1]
```

`select new SomeRecord(...)` is a **constructor expression**. The result set is three
scalar columns mapped straight into a record. No `Transfer`, no `Tag`, nothing added to
the persistence context, nothing to dirty-check at flush, nothing to lazily initialise.

### The four kinds of projection

| Kind | Looks like | Notes |
|---|---|---|
| **Constructor expression** | `select new Dto(a, b)` in JPQL | used here; explicit, works with aggregates |
| **Interface (closed)** | `interface View { String getReference(); }` | Spring Data generates a proxy and selects only those columns |
| **Interface (open)** | `@Value("#{target.a + ' ' + target.b}")` | SpEL over the entity — **loads the whole entity**, so no query saving |
| **Class-based DTO** | a record returned from a derived query | Spring Data matches constructor parameter names to properties |

Closed interface projections are the lightest way to trim columns off a derived query.
Constructor expressions are the right tool when you need aggregates or joins, as here.

`StatementLine` is a slightly different thing — it is mapped in Java from an entity that
was already loaded (`StatementLine.from(entry)`), not selected as a projection. That is
deliberate: the Specification must filter over the entity model, and the counterparty
depends on the row's direction, which is easier to express in Java than in SQL. The
important part is unchanged: **an entity never leaves the service.**

---

## 5. Measuring it yourself

The query counter used above is Hibernate's built-in statistics, already enabled in
`application.yaml` via `hibernate.generate_statistics: true`:

```java
Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
stats.clear();
// ... do the thing ...
long queries = stats.getPrepareStatementCount();
```

Asserting on that number is what turns "I think this is efficient" into a test that fails
when someone adds an innocent-looking getter call. `Phase3StatementTest#fixedQueryCount`
asserts the property that matters:

```java
assertThat(forFifty).isLessThanOrEqualTo(forFive);   // more rows must never cost more queries
assertThat(forFive).isLessThanOrEqualTo(3);
```

For ad-hoc digging, add `-Dspring.jpa.show-sql=true` to any `./mvnw test` run and read the
SQL directly.

---

## Files

| File | Role |
|---|---|
| `StatementController` | binds query params and `Pageable`, returns `Page<StatementLine>` |
| `StatementService` | transaction boundary, maps entities to lines while the session is open |
| `StatementFilter` | the six optional filters, all nullable |
| `LedgerEntrySpecifications` | builds the dynamic WHERE clause |
| `StatementLine` | one statement row as seen from this account |
| `TagSpendRow` | the group-by projection target |
| `LedgerEntryRepository` | `JpaSpecificationExecutor` + the `@EntityGraph` override |
| `TransferRepository.spendByTag` | the constructor-expression aggregate |

---

## Not done yet

- **Ownership checks.** Anyone who knows an account number can read its statement. Phase 4
  (Spring Security + `@PreAuthorize` + an ownership rule) closes that.
- **Keyset pagination**, per the offset caveat above.
- **CSV / PDF export**, which is what a statement screen usually grows next.
- **Running balance recomputation** — `balance_after` is stored per leg, which is correct
  for an append-only ledger but means a back-dated correction would need a rebuild.
