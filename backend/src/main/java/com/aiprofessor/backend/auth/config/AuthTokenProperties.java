package com.aiprofessor.backend.auth.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "aiprofessor.auth.token")
public record AuthTokenProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotBlank String keyId,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @Min(32) int refreshTokenBytes
) {
    public AuthTokenProperties{
        if (accessTokenTtl != null && (accessTokenTtl.isZero() || accessTokenTtl.isNegative())) {
            throw new IllegalArgumentException("accessTokenTtl must be positive");
        }

        if (refreshTokenTtl != null && (refreshTokenTtl.isZero() || refreshTokenTtl.isNegative())) {
            throw new IllegalArgumentException("refreshTokenTtl must be positive");
        }
    }
}
