package com.aiprofessor.backend.auth.service;

import com.aiprofessor.backend.auth.dto.response.LoginResponse;

import java.util.Objects;

public record LoginResult(
        LoginResponse response,
        String refreshToken
) {

    public LoginResult {
        Objects.requireNonNull(response, "response must not be null");

        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refreshToken must not be blank");
        }
    }

    @Override
    public String toString() {
        return "LoginResult[" + "response=<redacted>, " + "refreshToken=<redacted>" + "]";
    }
}
