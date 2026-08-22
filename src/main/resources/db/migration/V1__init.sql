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
