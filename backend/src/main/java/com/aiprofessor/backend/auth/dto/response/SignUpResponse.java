package com.aiprofessor.backend.auth.dto.response;

import com.aiprofessor.backend.auth.domain.User;

import java.time.Instant;
import java.util.UUID;

public record SignUpResponse(
        UUID userId,
        String email,
        String displayName,
        Instant createdAt
) {

    public static SignUpResponse from(User user) {
        return new SignUpResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getCreatedAt()
        );
    }
}
