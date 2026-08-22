# Payment Wallet

A digital wallet backend built around a **double-entry ledger**, **idempotent transfers**,
and **explicit concurrency control** — the parts of a payments system that CRUD tutorials
skip.

Java 26 · Spring Boot 4.1 · Spring Data JPA / Hibernate 7 · PostgreSQL · Flyway · springdoc OpenAPI

> **The source lives on the [`dev`](https://github.com/MoatazNoaman2001/payment-wallet/tree/dev)
> branch.** This branch carries the documentation only.

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
[`LockingStrategyComparisonTest`](https://github.com/MoatazNoaman2001/payment-wallet/blob/dev/src/test/java/com/luv2code/paymentwallet/LockingStrategyComparisonTest.java).

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
`join fetch` rather than lazily during JSON serialisation. Errors are RFC 7807
`application/problem+json`.

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

Full DDL: [`V1__init.sql`](https://github.com/MoatazNoaman2001/payment-wallet/blob/dev/src/main/resources/db/migration/V1__init.sql)

---

## API

Interactive docs at **http://localhost:8080/swagger-ui.html**

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/users` | register a user (PENDING, ROLE_CUSTOMER) |
| `GET` | `/api/users/{publicId}` | fetch a user |
| `POST` | `/api/users/{publicId}/activation` | activate after KYC |
| `POST` | `/api/accounts` | open an account in a currency |
| `GET` | `/api/accounts/{accountNumber}` | fetch an account |
| `GET` | `/api/accounts?ownerPublicId=` | list an owner's accounts |
| `POST` | `/api/accounts/{accountNumber}/deposits` | settlement → wallet (`TOPUP`) |
| `POST` | `/api/accounts/{accountNumber}/withdrawals` | wallet → settlement (`WITHDRAWAL`) |
| `POST` | `/api/transfers` | wallet → wallet (`P2P`), needs `Idempotency-Key` |
| `GET` | `/api/transfers/{reference}` | fetch a transfer |

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

Flyway applies `V1..V4` on startup: schema, reference data (EGP/USD/EUR, roles, tags), and
the SYSTEM settlement accounts. Then open Swagger and:

1. `POST /api/users` — register
2. `POST /api/accounts` — open an EGP wallet
3. `POST /api/accounts/{number}/deposits` — fund it from settlement
4. `POST /api/transfers` — send money to a second wallet

```bash
./mvnw test
```

> Tests run against the same database and clear transfer/ledger/outbox tables in teardown.
> Run them before creating demo data, not after.

---

## Tests

| Suite | Covers |
|---|---|
| `Phase1IdentityAndAccountsTest` | registration, validation → RFC 7807, opening a wallet |
| `Phase2TransferEngineTest` | double entry, ledger reconciliation, idempotency, rejections, 100-way concurrency |
| `CashOperationsTest` | deposits and withdrawals against settlement |
| `LockingStrategyComparisonTest` | pessimistic vs optimistic, side by side |

---

## Deeper reading

[`transfer/README.md`](https://github.com/MoatazNoaman2001/payment-wallet/blob/dev/src/main/java/com/luv2code/paymentwallet/transfer/README.md) walks
through the transfer module in detail: why the ledger exists, what each of the five steps
in `execute()` defends against, and a full comparison of the two locking strategies with
measured numbers.

---

## Roadmap

- [x] Schema, migrations, reference data
- [x] Identity and accounts, DTOs, validation, RFC 7807 errors
- [x] Transfer engine — idempotency, locking, double entry, outbox
- [x] Deposits and withdrawals via settlement accounts
- [ ] Paginated statements, filtering, aggregate spend by tag
- [ ] Spring Security 6 + JWT, ownership checks
- [ ] Reconciliation job, outbox publisher, reversal flow
- [ ] Testcontainers, Actuator, structured logging

Not implemented yet: fees (a third ledger leg into a fee account), persisted `FAILED`
transfers, and multi-currency FX transfers.
