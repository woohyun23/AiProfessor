package com.aiprofessor.backend.auth.token;

import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class RefreshTokenGenerator {

    private static final String TOKEN_PREFIX = "rft_";

    private final AuthTokenProperties authTokenProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        byte[] randomBytes = new byte[authTokenProperties.refreshTokenBytes()];

        secureRandom.nextBytes(randomBytes);

        String encodedToken = Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        return TOKEN_PREFIX + encodedToken;
    }
}
