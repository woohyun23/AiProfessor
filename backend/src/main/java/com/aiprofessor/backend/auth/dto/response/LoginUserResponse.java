package com.aiprofessor.backend.auth.dto.response;

import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.domain.UserRole;

import java.util.UUID;

public record LoginUserResponse(
        UUID userId,
        String email,
        String displayName,
        UserRole role
) {

    public static LoginUserResponse from(User user) {
        return new LoginUserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole()
        );
    }
}
