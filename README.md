# Payment Wallet

A digital wallet backend built around a **double-entry ledger**, **idempotent transfers**,
and **explicit concurrency control** — the parts of a payments system that CRUD tutorials
skip.

Java 26 · Spring Boot 4.1 · Spring Data JPA / Hibernate 7 · PostgreSQL · Flyway · springdoc OpenAPI

---

## The headline result

The transfer engine is verified under real concurrency, not just happy-path tested.
100 threads try to send 1 EGP each from an account holding 50 EGP, simultaneously:

```
>>>>> PESSIMISTIC (select ... for update)    succeeded=50 rejected=50 failed=0 retries=0    wall=743ms
>>>>> OPTIMISTIC  (@Version + retry)         succeeded=50 rejected=50 failed=0 retries=420  wall=737ms
```

Exactly 50 succeed. Final balance is exactly 0. No oversell, no lost update, and the
ledger reconciles to the balance afterwards. Both locking strategies are implemented so
the trade-off can be measured rather than argued about — see
[`LockingStrategyComparisonTest`](src/test/java/com/moataz/paymentwallet/LockingStrategyComparisonTest.java).

---

## Design decisions

**Money is `NUMERIC(19,4)` → `BigDecimal`, never `double`.** `0.1 + 0.2` is
`0.30000000000000004` in binary floating point and the error compounds. Comparisons use
`compareTo(...) == 0`, because `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is
`false`.

**The ledger is the source of truth; `account.balance` is a cached projection.**
`ledger_entry` is append-only — nothing in the codebase ever updates or deletes a row.
The invariant, asserted in tests:

```
account.balance == SUM(credits) - SUM(debits)   over that account's ledger entries
```

**Every transfer writes exactly two ledger legs**: `DEBIT` the source, `CREDIT` the
destination, both with strictly positive amounts. Money can never be created, only moved.
Across the whole system, every currency sums to zero:

```
 currency | customer_money | settlement | total
----------+----------------+------------+--------
 USD      |      1000.0000 | -1000.0000 | 0.0000
```

**Deposits and withdrawals are transfers against a SYSTEM settlement account**, not a
special case. A settlement balance of −1000 USD means "1000 USD entered the system from
outside" — a number that should mirror the real bank account. It is the only account type
permitted to go negative:

```sql
CHECK (balance >= 0 OR type = 'SYSTEM')
```

**Idempotency is enforced by the database, not by a check.** Clients send an
`Idempotency-Key` header; retries return the original transfer instead of moving money
twice. The lookup is the fast path, the unique index is the guarantee:

```sql
CREATE UNIQUE INDEX uq_transfer_idem ON transfer(initiated_by, idempotency_key);
```

**Locks are always acquired in ascending account id.** This breaks the circular-wait
condition, so concurrent A→B and B→A transfers cannot deadlock.

**Flyway owns the schema; Hibernate runs with `ddl-auto: validate`.** The application
refuses to start if the entities and the migrated schema disagree. Migrations are
append-only — a mistake is corrected by a new versioned migration, never by editing an
applied one (see `V4__restore_system_accounts.sql`).

**Entities never cross the HTTP boundary.** Requests and responses are Java records with
Bean Validation; `open-in-view` is `false`, so associations are fetched deliberately with
`join fetch` or `@EntityGraph` rather than lazily during JSON serialisation. Errors are
RFC 7807 `application/problem+json`.

**Identity comes from the token, never from the request.** Requests used to carry
`initiatorPublicId`, which meant the server believed whatever the client claimed. That
field is deleted: the caller is read from the authenticated principal. Authentication
answers *who are you*, roles answer *what kind of thing may you do*, and an ownership
check answers *is this row yours* — the third is the one that stops an authenticated
customer reading someone else's statement, and it is asserted in tests.

**Tokens: short-lived JWT plus a rotating refresh token.** The access token is a 15-minute
HS256 JWT delivered in an `HttpOnly` cookie, so a cross-site script cannot read it, with
`Authorization: Bearer` accepted as a fallback for Swagger and curl. The refresh token is
an opaque random string stored as a SHA-256 hash, single use, and rotated on every use;
replaying a spent one revokes every token descended from that login.

**The browser UI needed no new auth.** Because the access token is delivered as an
HttpOnly cookie, a form login sets it and the browser attaches it to every later page
request on its own — no JavaScript, no token handling in the client. CSRF protection is
enabled for the pages (cookies are sent automatically, so a cross-site form post would
otherwise be authenticated) and left off for `/api/**`, whose clients send an explicit
header that no cross-site form can forge.

**Every log line carries a request id.** A servlet filter puts one in the SLF4J MDC and
echoes it as `X-Request-Id`, so a single request can be followed through interleaved
concurrent logs:

```
08:44:22.076 [20b1a9f3] WARN c.m.p.auth.TokenService :
    Refresh token reuse detected for user 92eaecb5-... - revoked 1 tokens in family 3c75a2aa-...
```

**The ledger is checked, not trusted.** A scheduled job recomputes every balance from the
ledger in a single SQL statement and reports the accounts that disagree. It reports rather
than repairs: silently fixing a balance would hide the bug that moved it.

**The outbox is drained with `SKIP LOCKED`.** The publisher claims a batch with
`select ... for update skip locked`, so several instances take disjoint batches instead of
blocking each other — the standard queue-in-a-database pattern. Delivery is at-least-once,
so consumers deduplicate on the transfer reference.

**Mistakes are corrected forward, never erased.** Reversing a transfer writes a new
`REVERSAL` in the opposite direction with its own ledger legs and marks the original
`REVERSED`. Nothing is deleted or edited, so the history shows both the payment and the
undo. A partial unique index makes "at most one reversal per transfer" a database
guarantee rather than a service-layer hope:

```sql
CREATE UNIQUE INDEX uq_transfer_reversal ON transfer(reverses_transfer_id)
    WHERE reverses_transfer_id IS NOT NULL;
```

**Query count never grows with page size.** The statement endpoint filters through a
`Specification` and fetches through an `@EntityGraph`, so 6x the rows costs no extra
queries — measured and asserted, not assumed:

```
statement queries: page size 5 (5 rows) = 3, page size 50 (30 rows) = 2
naive lazy loading of the same 10 rows  = 12
```

---

## Schema

```
app_user ──< user_role >── role          currency ──< account ──< card
    │                                                     │
    ├── kyc_profile (shared PK)                           │
    │                                                     │
    └──< transfer >───────────────────────────────────────┘
             │        source_account_id / dest_account_id
             ├──< ledger_entry        (append-only, 2 legs per transfer)
             └──< transfer_tag >── tag

outbox_event   fx_rate   beneficiary
```

| Table | Role |
|---|---|
| `app_user`, `role`, `user_role`, `kyc_profile` | identity, roles, KYC |
| `currency`, `account`, `card`, `beneficiary` | money containers |
| `transfer` | the command: who moved what, where, and its status |
| `ledger_entry` | the truth: immutable debit/credit legs |
| `outbox_event` | transactional outbox for downstream publication |
| `fx_rate`, `tag`, `transfer_tag` | multi-currency and categorisation |

Full DDL: [`V1__init.sql`](src/main/resources/db/migration/V1__init.sql)

---

## API

Interactive docs at **http://localhost:8080/swagger-ui.html**

There is also a small server-rendered UI (Thymeleaf): sign in, account list, a filterable
statement, and an operations page. It is responsive and shares the API's authorization rules.

| Method | Path | Purpose | Access |
|---|---|---|---|
| `GET`/`POST` | `/login` | sign-in page and form | public |
| `GET`/`POST` | `/register` | self-service sign-up with inline validation | public |
| `GET` | `/users` (HTML) | customer list with status and account counts | staff |
| `GET` | `/users/{publicId}` (HTML) | profile, roles, accounts, open-account form | self or staff |
| `POST` | `/users/{publicId}/activate` | complete KYC activation | **admin** |
| `GET` | `/accounts` (HTML) | own accounts, or every account for staff | authenticated |
| `GET` | `/accounts/{n}/statement` (HTML) | paginated, filterable statement | owner or staff |
| `GET` | `/admin` (HTML) | reconciliation status and outbox backlog | **admin** |
| `POST` | `/api/auth/login` | issue access + refresh cookies | public |
| `POST` | `/api/auth/refresh` | rotate the refresh token | public |
| `POST` | `/api/auth/logout` | revoke every refresh token | authenticated |
| `GET` | `/api/auth/me` | the authenticated identity | authenticated |
| `POST` | `/api/users` | register a user (PENDING, ROLE_CUSTOMER) | public |
| `GET` | `/api/users/{publicId}` | fetch a user | self or admin |
| `POST` | `/api/users/{publicId}/activation` | activate after KYC | **admin** |
| `POST` | `/api/accounts` | open an account; `ownerPublicId` for another user needs staff | authenticated |
| `GET` | `/api/accounts/{accountNumber}` | fetch an account | owner |
| `GET` | `/api/accounts` | paginated; staff see every account, or one customer's via `ownerPublicId` | authenticated |
| `POST` | `/api/accounts/{accountNumber}/deposits` | settlement → wallet (`TOPUP`) | owner |
| `POST` | `/api/accounts/{accountNumber}/withdrawals` | wallet → settlement (`WITHDRAWAL`) | owner |
| `POST` | `/api/transfers` | wallet → wallet (`P2P`), needs `Idempotency-Key` | owner of **source** |
| `GET` | `/api/transfers/{reference}` | fetch a transfer | either party |
| `PUT` | `/api/transfers/{reference}/tags` | categorise a transfer | either party |
| `POST` | `/api/transfers/{reference}/reversal` | compensating REVERSAL transfer | **admin** |
| `GET` | `/actuator/health` | liveness, including a database check | public |
| `GET` | `/api/admin/reconciliation` | accounts whose balance disagrees with the ledger | **admin** |
| `POST` | `/api/admin/outbox/publish` | drain pending outbox events now | **admin** |
| `GET` | `/api/accounts/{accountNumber}/statement` | paginated, filterable statement | owner |
| `GET` | `/api/accounts/{accountNumber}/spend-by-tag` | monthly spend aggregate | owner |

Public identifiers are a UUID (`publicId`) or an account number — sequential database ids
are never exposed.

```bash
curl -X POST localhost:8080/api/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{"initiatorPublicId":"<uuid>","sourceAccountNumber":"PW...","destAccountNumber":"PW...",
       "amount":20.0000,"currencyCode":"EGP","type":"P2P","description":"lunch"}'
```

Send it twice with the same key: the second call returns the first transfer and the
balances do not move again.

| Situation | Status |
|---|---|
| invalid or missing fields | `400` with a per-field `errors` map |
| unknown account or user | `404` |
| insufficient funds, currency mismatch, frozen account, self-transfer | `422` |
| duplicate email or phone | `409` |

---

## Running it

Requires JDK 26 and a PostgreSQL server.

```bash
createdb payment_wallet          # or use an existing database
cp .env.example .env             # then fill in your connection details
```

`.env` is git-ignored and read natively by Spring Boot via
`spring.config.import: optional:file:.env[.properties]` — no extra dependency. The
datasource properties have **no fallback values**, so a missing variable fails at startup
instead of silently connecting somewhere unintended. In CI or a container, set `DB_URL`,
`DB_USERNAME` and `DB_PASSWORD` as real environment variables and skip the file.

```bash
./mvnw spring-boot:run
```

Flyway applies `V1..V7` on startup: schema, reference data (EGP/USD/EUR, roles, tags), and
the SYSTEM settlement accounts. Then open Swagger and:

1. `POST /api/users` — register
2. `POST /api/auth/login` — copy `accessToken`, paste it into Swagger's **Authorize** button
3. `POST /api/accounts` — open an EGP wallet
4. `POST /api/accounts/{number}/deposits` — fund it from settlement
5. `POST /api/transfers` — send money to a second wallet
6. `GET /api/accounts/{number}/statement` — see both legs

Staff logins are seeded: `admin@paymentwallet.local` / `admin12345` (`V5`) and
`teller@paymentwallet.local` / `teller12345` (`V7`).
Demo credentials only — change or remove them before deploying anything.

For a populated demo — two customers, funded wallets, tagged transfers and a withdrawal,
all created through the real services so every balance has ledger entries behind it:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--demo.seed=true
```

```bash
./mvnw test
```

### Or run it all in Docker

```bash
docker compose up --build            # app + Postgres 16
APP_PORT=8081 docker compose up      # if 8080 is already taken locally
docker compose up -d db              # just the database, run the app from your IDE
```

The image is a two-stage build: Maven and the JDK live in the build stage only, the
runtime is a JRE image running as a non-root user, and `HEALTHCHECK` polls
`/actuator/health`. Compose waits for Postgres to report healthy before starting the app,
because Flyway would fail against a database still coming up. Postgres is published on
**5433** so a local instance on 5432 is untouched, and `JWT_SECRET` is required rather
than defaulted.

> Tests run against the same database and clear transfer/ledger/outbox tables in teardown.
> Run them before creating demo data, not after.

---

## Tests

| Suite | Covers |
|---|---|
| `Phase1IdentityAndAccountsTest` | registration, validation → RFC 7807, opening a wallet |
| `Phase2TransferEngineTest` | double entry, ledger reconciliation, idempotency, rejections, 100-way concurrency |
| `CashOperationsTest` | deposits and withdrawals against settlement |
| `Phase3StatementTest` | pagination, composed filters, fixed query count, group-by projection |
| `LockingStrategyComparisonTest` | pessimistic vs optimistic, side by side |
| `Phase5ReliabilityTest` | drift detection, publish-once semantics, batch draining |
| `Phase5ReversalTest` | compensating reversal, idempotency, state machine, refusal when funds are spent |
| `Phase4SecurityTest` | 401 anonymous, 403 on someone else's account, refused transfer leaves balances untouched, refresh reuse detection |

---

## Deeper reading

[`transfer/README.md`](src/main/java/com/moataz/paymentwallet/transfer/README.md) walks
through the transfer module in detail: why the ledger exists, what each of the five steps
in `execute()` defends against, and a full comparison of the two locking strategies with
measured numbers.

[`auth/README.md`](src/main/java/com/moataz/paymentwallet/auth/README.md) covers the
security design: the three separate questions (authentication, authorization, ownership),
why the refresh token is opaque and stored hashed, how reuse detection revokes a token
family, and the XSS-versus-CSRF trade that comes with cookie delivery.

[`statement/README.md`](src/main/java/com/moataz/paymentwallet/statement/README.md)
covers the query side: pagination and its scaling limits, Specifications for dynamic
filtering, `@EntityGraph` versus `join fetch` and why collections break paginated fetch
joins, and the four kinds of JPA projection.

---

## Roadmap

- [x] Schema, migrations, reference data
- [x] Identity and accounts, DTOs, validation, RFC 7807 errors
- [x] Transfer engine — idempotency, locking, double entry, outbox
- [x] Deposits and withdrawals via settlement accounts
- [x] Paginated statements, filtering, aggregate spend by tag
- [x] Spring Security + JWT, refresh rotation, ownership checks
- [x] Reversal flow (compensating transfer, never a delete)
- [x] Reconciliation job and outbox publisher
- [x] Actuator health, correlation-id logging, Docker + Compose
- [x] Thymeleaf pages: login, accounts
- [x] Thymeleaf page: statement with filters and pagination
- [x] Thymeleaf: navigation, staff account list, operations page, responsive layout
- [x] Thymeleaf: registration, customer list, profile with activation and account opening
- [ ] Thymeleaf page: transfer form
- [ ] Testcontainers, metrics

Not implemented yet: fees (a third ledger leg into a fee account), persisted `FAILED`
transfers, and multi-currency FX transfers.
