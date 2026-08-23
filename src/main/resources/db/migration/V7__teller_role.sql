INSERT INTO role (name) VALUES ('ROLE_TELLER');

ALTER TABLE account ADD COLUMN opened_by BIGINT REFERENCES app_user(id);

INSERT INTO app_user (email, phone, password_hash, full_name, status)
VALUES ('teller@paymentwallet.local', '+000000000002',
        '$2a$10$EI.axDdU5ip/YvHBHnB0p.Ncvkv.MP82HXbo/BweI8SSZLThhgU4.',
        'Demo Teller', 'ACTIVE');

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
FROM app_user u, role r
WHERE u.email = 'teller@paymentwallet.local'
  AND r.name IN ('ROLE_TELLER', 'ROLE_CUSTOMER');
