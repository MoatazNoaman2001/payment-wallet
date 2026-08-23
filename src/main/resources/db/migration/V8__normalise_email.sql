UPDATE app_user SET email = lower(trim(email)) WHERE email <> lower(trim(email));

CREATE UNIQUE INDEX uq_app_user_email_lower ON app_user (lower(email));
