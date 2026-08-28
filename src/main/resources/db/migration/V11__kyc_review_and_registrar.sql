-- Who keyed this person in? NULL means they registered themselves.
ALTER TABLE app_user
    ADD COLUMN registered_by BIGINT REFERENCES app_user(id);

-- kyc_profile has existed since V1 but nothing ever wrote to it. These three
-- columns turn it from a record into a reviewable decision.
ALTER TABLE kyc_profile
    ADD COLUMN submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN reviewed_by  BIGINT REFERENCES app_user(id),
    ADD COLUMN review_note  VARCHAR(255);

CREATE INDEX idx_kyc_profile_pending ON kyc_profile (submitted_at)
    WHERE verified_at IS NULL;
