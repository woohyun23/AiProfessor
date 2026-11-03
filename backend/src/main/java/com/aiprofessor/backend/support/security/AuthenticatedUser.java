package com.aiprofessor.backend.support.security;

import java.util.Objects;
import java.util.UUID;

public record AuthenticatedUser(
        UUID userId,
        String role
) {

    public AuthenticatedUser {
        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        Objects.requireNonNull(
                role,
                "role must not be null"
        );
    }
}
