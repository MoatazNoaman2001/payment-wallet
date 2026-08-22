-- Deposits and withdrawals are transfers against a settlement account that
-- represents the outside world. It must be allowed to run negative: a negative
-- balance there means "money owed to / held outside the wallet system".

ALTER TABLE account DROP CONSTRAINT account_balance_check;
ALTER TABLE account ADD CONSTRAINT ck_account_balance
    CHECK (balance >= 0 OR type = 'SYSTEM');

INSERT INTO app_user (email, phone, password_hash, full_name, status)
VALUES ('system@paymentwallet.local', '+000000000000', 'N/A', 'System', 'ACTIVE');

INSERT INTO account (account_number, user_id, currency_code, type, status, balance)
SELECT 'SYSTEM-' || c.code, u.id, c.code, 'SYSTEM', 'ACTIVE', 0
FROM currency c
CROSS JOIN app_user u
WHERE u.email = 'system@paymentwallet.local';

