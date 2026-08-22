-- Repair migration. The settlement accounts seeded by V3 were deleted by a test run
-- (deleteAll() does not know the difference between test data and seeded data).
-- V3 cannot be edited once applied, so the fix goes forward in a new migration.
--
-- Written to be safe to run against a database where the rows already exist.

INSERT INTO app_user (email, phone, password_hash, full_name, status)
SELECT 'system@paymentwallet.local', '+000000000000', 'N/A', 'System', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM app_user WHERE email = 'system@paymentwallet.local');

INSERT INTO account (account_number, user_id, currency_code, type, status, balance)
SELECT 'SYSTEM-' || c.code, u.id, c.code, 'SYSTEM', 'ACTIVE', 0
FROM currency c
CROSS JOIN app_user u
WHERE u.email = 'system@paymentwallet.local'
  AND NOT EXISTS (
      SELECT 1 FROM account a
      WHERE a.type = 'SYSTEM' AND a.currency_code = c.code);
