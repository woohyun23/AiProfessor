CREATE TABLE users
(
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(10)  NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at TIMESTAMPTZ,
    withdrawn_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_users
        PRIMARY KEY (id),

    CONSTRAINT uk_users_email
        UNIQUE (email),

    CONSTRAINT chk_users_email_normalized
        CHECK (email = LOWER(BTRIM(email))),

    CONSTRAINT chk_users_display_name_length
        CHECK (CHAR_LENGTH(display_name) BETWEEN 2 AND 10),

    CONSTRAINT chk_users_role
        CHECK (role IN ('USER', 'ADMIN')),

    CONSTRAINT chk_users_status
        CHECK (status IN ('ACTIVE', 'LOCKED', 'WITHDRAWN'))
);