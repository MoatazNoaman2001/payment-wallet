-- Reference data: rows the application cannot function without.
-- Kept in a migration (not data.sql) so every environment gets the same values,
-- and so the insert is versioned and replayable like any other schema change.

INSERT INTO currency (code, name, minor_units) VALUES
    ('EGP', 'Egyptian Pound', 2),
    ('USD', 'US Dollar',      2),
    ('EUR', 'Euro',           2);

INSERT INTO role (name) VALUES
    ('ROLE_CUSTOMER'),
    ('ROLE_MERCHANT'),
    ('ROLE_ADMIN');

INSERT INTO tag (name) VALUES
    ('groceries'),
    ('rent'),
    ('salary'),
    ('transport'),
    ('utilities'),
    ('entertainment');
