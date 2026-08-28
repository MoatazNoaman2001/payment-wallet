ALTER TABLE account
    ADD COLUMN status_reason     VARCHAR(255),
    ADD COLUMN status_changed_at TIMESTAMPTZ,
    ADD COLUMN status_changed_by BIGINT REFERENCES app_user(id);
