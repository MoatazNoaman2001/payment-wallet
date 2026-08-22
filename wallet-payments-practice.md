# Digital Wallet & Payments — Schema + Spring Boot Practice Plan

**Stack:** Java 21, Spring Boot 3.x, Spring Data JPA, PostgreSQL 16, Flyway, Testcontainers.

**Why this domain:** it forces real transactional thinking (double-entry ledger, idempotency, concurrency) instead of CRUD. Every design decision below has an interview question attached to it.

---

## 1. Design principles baked into the schema

| Decision | Why |
|---|---|
| Money as `NUMERIC(19,4)` → `BigDecimal` | Never `float`/`double`. Binary floating point cannot represent 0.1. |
| `VARCHAR` + `CHECK` instead of native PG `ENUM` | PG enums are painful to migrate and to map in Hibernate. Map with `@Enumerated(EnumType.STRING)`. |
| Ledger is append-only, balance is a cached projection | Source of truth = sum of ledger entries. `account.balance` exists for speed and must be reconcilable. |
| `version` columns | Optimistic locking (`@Version`). Concurrency without holding row locks. |
| `idempotency_key` on transfers | The single most-asked payments question: "what if the client retries?" |
| Surrogate `BIGSERIAL` PKs + business `reference` | Never expose sequential DB ids in APIs. |
| `created_at`/`updated_at` everywhere | Wire to `@CreatedDate`/`@LastModifiedDate` + `@EnableJpaAuditing`. |

---

## 2. Schema (Flyway `V1__init.sql`)

```sql
-- ---------- identity ----------
CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    public_id       UUID        NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    phone           VARCHAR(20)  NOT NULL UNIQUE,
    password_hash   VARCHAR(100) NOT NULL,
    full_name       VARCHAR(150) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','CLOSED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE role (
    id    BIGSERIAL PRIMARY KEY,
    name  VARCHAR(50) NOT NULL UNIQUE   -- ROLE_CUSTOMER, ROLE_MERCHANT, ROLE_ADMIN
);

CREATE TABLE user_role (                 -- @ManyToMany
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE kyc_profile (               -- @OneToOne, shared PK
    user_id       BIGINT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    national_id   VARCHAR(20) NOT NULL UNIQUE,
    date_of_birth DATE        NOT NULL,
    address       VARCHAR(255),
    tier          VARCHAR(20) NOT NULL DEFAULT 'BASIC'
                  CHECK (tier IN ('BASIC','VERIFIED','ENHANCED')),
    verified_at   TIMESTAMPTZ
);

-- ---------- money ----------
CREATE TABLE currency (
    code        CHAR(3) PRIMARY KEY,      -- EGP, USD, EUR
    name        VARCHAR(50) NOT NULL,
    minor_units SMALLINT    NOT NULL DEFAULT 2
);

CREATE TABLE account (
    id             BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(24)  NOT NULL UNIQUE,
    user_id        BIGINT       NOT NULL REFERENCES app_user(id),
    currency_code  CHAR(3)      NOT NULL REFERENCES currency(code),
    type           VARCHAR(20)  NOT NULL
                   CHECK (type IN ('WALLET','SAVINGS','MERCHANT','SYSTEM')),
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    balance        NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    daily_limit    NUMERIC(19,4),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_account_user ON account(user_id);

CREATE TABLE card (                       -- @OneToMany from account
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT      NOT NULL REFERENCES account(id),
    last_four   CHAR(4)     NOT NULL,
    brand       VARCHAR(20) NOT NULL CHECK (brand IN ('VISA','MASTERCARD','MEEZA')),
    expiry_month SMALLINT   NOT NULL CHECK (expiry_month BETWEEN 1 AND 12),
    expiry_year  SMALLINT   NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE','BLOCKED','EXPIRED'))
);

CREATE TABLE beneficiary (
    id                  BIGSERIAL PRIMARY KEY,
    owner_user_id       BIGINT       NOT NULL REFERENCES app_user(id),
    alias               VARCHAR(80)  NOT NULL,
    external_account_no VARCHAR(34),
    bank_code           VARCHAR(11),
    internal_account_id BIGINT REFERENCES account(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_benef UNIQUE (owner_user_id, alias),
    CONSTRAINT ck_benef_target CHECK (
        (internal_account_id IS NOT NULL) OR (external_account_no IS NOT NULL))
);

-- ---------- the core ----------
CREATE TABLE transfer (
    id                BIGSERIAL PRIMARY KEY,
    reference         VARCHAR(32)  NOT NULL UNIQUE,
    idempotency_key   VARCHAR(64)  NOT NULL,
    initiated_by      BIGINT       NOT NULL REFERENCES app_user(id),
    source_account_id BIGINT       NOT NULL REFERENCES account(id),
    dest_account_id   BIGINT       NOT NULL REFERENCES account(id),
    amount            NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency_code     CHAR(3)      NOT NULL REFERENCES currency(code),
    fee               NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (fee >= 0),
    type              VARCHAR(20)  NOT NULL
                      CHECK (type IN ('P2P','TOPUP','WITHDRAWAL','MERCHANT_PAYMENT','REVERSAL')),
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','POSTED','FAILED','REVERSED')),
    failure_reason    VARCHAR(255),
    description       VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    posted_at         TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_diff_accounts CHECK (source_account_id <> dest_account_id)
);
CREATE UNIQUE INDEX uq_transfer_idem ON transfer(initiated_by, idempotency_key);
CREATE INDEX idx_transfer_src_created ON transfer(source_account_id, created_at DESC);

CREATE TABLE ledger_entry (               -- immutable, append-only
    id           BIGSERIAL PRIMARY KEY,
    transfer_id  BIGINT       NOT NULL REFERENCES transfer(id),
    account_id   BIGINT       NOT NULL REFERENCES account(id),
    direction    VARCHAR(6)   NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount       NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    balance_after NUMERIC(19,4) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_account_created ON ledger_entry(account_id, created_at DESC);
CREATE UNIQUE INDEX uq_ledger_leg ON ledger_entry(transfer_id, account_id, direction);

-- ---------- supporting ----------
CREATE TABLE fx_rate (
    id             BIGSERIAL PRIMARY KEY,
    base_currency  CHAR(3) NOT NULL REFERENCES currency(code),
    quote_currency CHAR(3) NOT NULL REFERENCES currency(code),
    rate           NUMERIC(19,8) NOT NULL CHECK (rate > 0),
    valid_from     TIMESTAMPTZ NOT NULL,
    valid_to       TIMESTAMPTZ
);

CREATE TABLE tag (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(40) NOT NULL UNIQUE     -- groceries, rent, salary...
);
CREATE TABLE transfer_tag (               -- second @ManyToMany
    transfer_id BIGINT NOT NULL REFERENCES transfer(id) ON DELETE CASCADE,
    tag_id      BIGINT NOT NULL REFERENCES tag(id),
    PRIMARY KEY (transfer_id, tag_id)
);

CREATE TABLE outbox_event (               -- transactional outbox pattern
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    event_type     VARCHAR(60) NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_event(created_at)
    WHERE published_at IS NULL;          -- partial index
```

**Invariant to enforce in code and verify in tests:** for every `POSTED` transfer, `SUM(debits) = SUM(credits)` across its ledger entries, and every account's `balance` equals `SUM(credits) - SUM(debits)` over its ledger.

---

## 3. JPA mapping traps to hit deliberately

These are the ones that actually come up in interviews. Get each one wrong once, then fix it — you'll never forget it.

1. **`@ManyToOne` defaults to `EAGER`.** Set `fetch = FetchType.LAZY` on every one. Then watch `LazyInitializationException` appear and fix it properly (DTO projection / `@EntityGraph`), not by turning `open-in-view` back on.
2. **`spring.jpa.open-in-view=false`** in `application.yml` from day one.
3. **N+1:** list 50 transfers with their source account + user. Turn on `spring.jpa.properties.hibernate.generate_statistics=true`, count the queries, then fix with `JOIN FETCH` or `@EntityGraph`.
4. **`@Enumerated(EnumType.STRING)`** — the default is `ORDINAL`, which silently corrupts data when you reorder the enum.
5. **`@Version` + `OptimisticLockingFailureException`** — write a test that fires two concurrent transfers from the same account and asserts one retries.
6. **`@Transactional` self-invocation** — call a `@Transactional` method from another method in the same class and observe that it does nothing. Understand the proxy.
7. **`equals`/`hashCode` on entities** — never use the generated `id` naively (breaks for unsaved entities in a `Set`). Use `public_id`/business key.
8. **`ddl-auto: validate`**, never `update`. Flyway owns the schema.
9. **`BigDecimal.compareTo` not `equals`** — `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is `false`.

---

## 4. Build phases

### Phase 0 — Setup
Docker Compose with Postgres, Flyway migrations, Testcontainers wired into the test slice.
**Done when:** `./mvnw verify` spins a real Postgres, runs migrations, and passes.

### Phase 1 — Identity & accounts
Entities, repositories, DTOs (Java records), MapStruct or manual mappers, Bean Validation, a `@RestControllerAdvice` returning RFC 7807 `ProblemDetail`.
**Done when:** you can register a user, open an EGP wallet, and every validation failure returns a clean structured error.

### Phase 2 — The transfer engine (the real work)
`TransferService.execute(command)` inside one `@Transactional`:
1. Check idempotency key → if a transfer exists, return the original result.
2. Lock both accounts in **deterministic order by id** (prevents deadlock) — try both `@Lock(PESSIMISTIC_WRITE)` and the `@Version` optimistic approach and compare.
3. Validate: same currency, sufficient balance, account status, daily limit.
4. Write two `ledger_entry` rows, update both balances, set status `POSTED`.
5. Insert an `outbox_event` in the same transaction.

**Done when:** a concurrency test fires 100 parallel transfers of 1 EGP from an account holding 50 EGP, and exactly 50 succeed with the final balance at 0 — no oversell, no lost update.

### Phase 3 — Queries
Paginated statement endpoint, filtering by date/type/amount with `Specification`, projections that never return entities, a monthly-spend-by-tag aggregate with a native or JPQL group-by.
**Done when:** the statement endpoint runs in a fixed number of queries regardless of page size.

### Phase 4 — Security
Spring Security 6, JWT, `@PreAuthorize` plus an ownership check (a user must not read another user's account — write the test that proves it).

### Phase 5 — Reliability
`@Scheduled` reconciliation job that recomputes balances from the ledger and alerts on drift. Outbox publisher. Reversal flow that creates a compensating `REVERSAL` transfer rather than deleting rows.

### Phase 6 — Polish
Testcontainers integration tests, `@DataJpaTest` slices, Springdoc OpenAPI, Actuator + Micrometer, structured logging with a correlation id per request.

---

## 5. Stretch goals

- Multi-currency transfer using `fx_rate` with a three-leg ledger entry (source, FX spread account, destination).
- Convert the whole thing to a state machine for `transfer.status` with explicit allowed transitions.
- Add Redis caching on `fx_rate` and demonstrate the cache stampede problem.
- Swap the outbox publisher to Kafka — you already know the pattern from Al-vora.
