package com.aiprofessor.backend.auth.security;

import com.aiprofessor.backend.auth.domain.UserRole;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final String INVALID_TOKEN_ERROR = "Invalid token";
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final Set<String> ALLOWED_ROLES = Set.of(UserRole.USER.name(), UserRole.ADMIN.name());

    private final Clock clock;
    private final Duration clockSkew;

    public AccessTokenClaimsValidator(
            Clock clock,
            Duration clockSkew
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.clockSkew = Objects.requireNonNull(clockSkew, "clockSkew must not be null");

        if (clockSkew.isNegative()) {
            throw new IllegalArgumentException("clockSkew must not be negative");
        }
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!ACCESS_TOKEN_TYPE.equals(jwt.getClaim("typ"))) {
            return failure("Access Token type is invalid");
        }

        if (!isUuid(jwt.getSubject())) {
            return failure("Access Token subject is invalid");
        }

        String role = jwt.getClaimAsString("role");

        if (!ALLOWED_ROLES.contains(role)) {
            return failure("Access Token role is invalid");
        }

        if (!isUuid(jwt.getId())) {
            return failure("Access Token id is invalid");
        }

        Instant issuedAt = jwt.getIssuedAt();

        if (issuedAt == null) {
            return failure("Access Token issuedAt is invalid");
        }

        Instant latestAllowedIssuedAt = clock.instant().plus(clockSkew);

        if (issuedAt.isAfter(latestAllowedIssuedAt)) {
            return failure("Access Token issuedAt is too far in the future");
        }

        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult failure(String description) {
        OAuth2Error error = new OAuth2Error(
                INVALID_TOKEN_ERROR,
                description,
                null
        );

        return OAuth2TokenValidatorResult.failure(error);
    }

    private boolean isUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
