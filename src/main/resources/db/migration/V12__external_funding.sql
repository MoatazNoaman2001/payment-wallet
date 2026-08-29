-- Cash already had one settlement account per currency. Every payment provider
-- needs its own, because money sitting at Stripe is not money sitting in the
-- drawer: the provider holds it for days before paying out, and reconciling
-- against them means knowing what they owe you separately.
ALTER TABLE account ADD COLUMN provider VARCHAR(20);

CREATE UNIQUE INDEX uq_account_cash_settlement ON account (currency_code)
    WHERE type = 'SYSTEM' AND provider IS NULL;

CREATE UNIQUE INDEX uq_account_provider_clearing ON account (currency_code, provider)
    WHERE type = 'SYSTEM' AND provider IS NOT NULL;

-- A payment intent is the wallet's own record of money it has asked for or sent
-- but does not yet have. It is deliberately separate from `transfer`: a transfer
-- is a fact about the ledger, an intent is a conversation with somebody else that
-- may still fail.
CREATE TABLE payment_intent (
    id                 BIGSERIAL PRIMARY KEY,
    reference          VARCHAR(32)  NOT NULL UNIQUE,
    user_id            BIGINT       NOT NULL REFERENCES app_user(id),
    account_id         BIGINT       NOT NULL REFERENCES account(id),
    direction          VARCHAR(12)  NOT NULL CHECK (direction IN ('DEPOSIT', 'WITHDRAWAL')),
    provider           VARCHAR(20)  NOT NULL,
    method             VARCHAR(30),
    amount             NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency_code      CHAR(3)      NOT NULL REFERENCES currency(code),
    status             VARCHAR(20)  NOT NULL,
    provider_reference VARCHAR(120),
    redirect_url       VARCHAR(500),
    deposit_address    VARCHAR(120),
    idempotency_key    VARCHAR(80)  NOT NULL,
    transfer_id        BIGINT       REFERENCES transfer(id),
    failure_reason     VARCHAR(255),
    initiated_by       BIGINT       REFERENCES app_user(id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at       TIMESTAMPTZ,
    version            BIGINT       NOT NULL DEFAULT 0
);

-- the same guarantee /api/transfers gives: retrying a submission never charges twice
CREATE UNIQUE INDEX uq_payment_intent_idem ON payment_intent (user_id, idempotency_key);

-- and the provider's own id maps to exactly one intent, so a replayed webhook
-- can find what it refers to instead of creating a second one
CREATE UNIQUE INDEX uq_payment_intent_provider_ref ON payment_intent (provider, provider_reference)
    WHERE provider_reference IS NOT NULL;

CREATE INDEX idx_payment_intent_open ON payment_intent (provider, created_at)
    WHERE status IN ('REQUIRES_ACTION', 'PENDING');

CREATE INDEX idx_payment_intent_user ON payment_intent (user_id, created_at DESC);

-- Providers retry webhooks until they get a 2xx, and they do not promise to
-- deliver in order or exactly once. Recording every delivery under a unique
-- provider event id is what turns "at least once" into "effectively once".
CREATE TABLE webhook_event (
    id                 BIGSERIAL PRIMARY KEY,
    provider           VARCHAR(20)  NOT NULL,
    provider_event_id  VARCHAR(120) NOT NULL,
    event_type         VARCHAR(80),
    payload            TEXT         NOT NULL,
    signature_verified BOOLEAN      NOT NULL DEFAULT false,
    received_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at       TIMESTAMPTZ,
    error              VARCHAR(500)
);

CREATE UNIQUE INDEX uq_webhook_event ON webhook_event (provider, provider_event_id);

CREATE INDEX idx_webhook_event_unprocessed ON webhook_event (received_at)
    WHERE processed_at IS NULL;
