package com.aiprofessor.backend.auth.dto.response;

import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.token.IssuedAccessToken;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        LoginUserResponse user
) {

    private static final String BEARER_TOKEN_TYPE = "Bearer";

    public static LoginResponse of(
            User user,
            IssuedAccessToken accessToken
    ) {
        return new LoginResponse(
                accessToken.tokenValue(),
                BEARER_TOKEN_TYPE,
                accessToken.expiresInSeconds(),
                LoginUserResponse.from(user)
        );
    }
}
