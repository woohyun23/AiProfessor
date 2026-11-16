package com.aiprofessor.backend.auth.service;

import com.aiprofessor.backend.auth.token.IssuedAccessToken;

public record RefreshRotationResult(
        IssuedAccessToken accessToken,
        String refreshToken
) {

    @Override
    public String toString() {
        return "RefreshRotationResult [accessToken=<redacted>, refreshToken=<redacted>]";
    }
}
