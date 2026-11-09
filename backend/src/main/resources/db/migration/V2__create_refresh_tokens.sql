CREATE TABLE refresh_tokens
(
    id                     UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id                UUID        NOT NULL,
    token_hash             CHAR(64)    NOT NULL,
    token_family_id        UUID        NOT NULL,
    expires_at             TIMESTAMPTZ NOT NULL,
    revoked_at             TIMESTAMPTZ,
    replaced_by_token_id   UUID,
    created_ip_hash        CHAR(64),
    user_agent             VARCHAR(500),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_refresh_tokens
        PRIMARY KEY (id),

    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id)
            REFERENCES users (id),

    CONSTRAINT fk_refresh_tokens_replaced_by
        FOREIGN KEY (replaced_by_token_id)
            REFERENCES refresh_tokens (id),

    CONSTRAINT uk_refresh_tokens_token_hash
        UNIQUE (token_hash),

    CONSTRAINT chk_refresh_tokens_token_hash
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),

    CONSTRAINT chk_refresh_tokens_ip_hash
        CHECK (
            created_ip_hash IS NULL
            OR created_ip_hash ~ '^[0-9a-f]{64}$'
        ),

    CONSTRAINT chk_refresh_tokens_expiration
        CHECK (expires_at > created_at),

    CONSTRAINT chk_refresh_tokens_replacement
        CHECK (
            replaced_by_token_id IS NULL
            OR revoked_at IS NOT NULL
        )
);

CREATE INDEX idx_refresh_tokens_user_active
    ON refresh_tokens (user_id, revoked_at, expires_at);

CREATE INDEX idx_refresh_tokens_family_active
    ON refresh_tokens (token_family_id, revoked_at);