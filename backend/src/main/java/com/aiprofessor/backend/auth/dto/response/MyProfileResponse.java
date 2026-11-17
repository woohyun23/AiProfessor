package com.aiprofessor.backend.auth.dto.response;

import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.domain.UserRole;

import java.time.Instant;
import java.util.UUID;

public record MyProfileResponse(
        UUID userId,
        String email,
        String displayName,
        UserRole role,
        Instant createdAt
) {
    public static MyProfileResponse from(User user) {
        return new MyProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}
