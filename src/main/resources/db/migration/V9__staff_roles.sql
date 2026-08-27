INSERT INTO role (name)
SELECT r.name FROM (VALUES
    ('ROLE_SUPERVISOR'),
    ('ROLE_OPS'),
    ('ROLE_COMPLIANCE'),
    ('ROLE_AUDITOR')
) AS r(name)
WHERE NOT EXISTS (SELECT 1 FROM role existing WHERE existing.name = r.name);
