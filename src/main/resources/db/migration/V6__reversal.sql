-- A reversal is a new compensating transfer, never an update or a delete: the ledger
-- stays append-only. This column links the compensating transfer back to the original.
--
-- The UNIQUE constraint is the real guarantee that a transfer can be reversed only once.
-- The service checks first, but two concurrent requests can both pass that check; only
-- one can insert.

ALTER TABLE transfer
    ADD COLUMN reverses_transfer_id BIGINT REFERENCES transfer(id);

CREATE UNIQUE INDEX uq_transfer_reversal ON transfer(reverses_transfer_id)
    WHERE reverses_transfer_id IS NOT NULL;
