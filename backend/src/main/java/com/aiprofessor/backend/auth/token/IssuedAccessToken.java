package com.aiprofessor.backend.auth.token;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record IssuedAccessToken(
        // 로그인 응답의 accessToken
        String tokenValue,
        // JWT iat
        Instant issuedAt,
        // JWT exp
        Instant expiresAt
) {

    public IssuedAccessToken {
        if (tokenValue == null || tokenValue.isBlank()) {
            throw new IllegalArgumentException("tokenValue must not be blank");
        }

        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    // 로그인 응답의 expiresIn
    public long expiresInSeconds() {
        return Duration.between(
                issuedAt,
                expiresAt
        ).toSeconds();
    }
}
