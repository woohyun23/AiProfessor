package com.aiprofessor.backend.auth.dto.response;

import com.aiprofessor.backend.auth.token.IssuedAccessToken;

public record RefreshResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
    public static RefreshResponse from(IssuedAccessToken accessToken) {
        return new RefreshResponse(
                accessToken.tokenValue(),
                "Bearer",
                accessToken.expiresInSeconds()
        );
    }
}
