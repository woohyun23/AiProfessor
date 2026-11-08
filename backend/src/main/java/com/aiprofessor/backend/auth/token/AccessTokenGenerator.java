package com.aiprofessor.backend.auth.token;

import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import com.aiprofessor.backend.auth.domain.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AccessTokenGenerator {

    private static final String JWT_HEADER_TYPE = "JWT";
    private static final String ACCESS_TOKEN_TYPE = "access";

    private final JwtEncoder jwtEncoder;
    private final AuthTokenProperties authTokenProperties;
    private final Clock clock;

    public IssuedAccessToken generate(
            UUID userId,
            UserRole role
    ) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(authTokenProperties.accessTokenTtl());
        String jwtId = UUID.randomUUID().toString();

        JwsHeader header = JwsHeader
                .with(SignatureAlgorithm.RS256)
                .type(JWT_HEADER_TYPE)
                .keyId(authTokenProperties.keyId())
                .build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(authTokenProperties.issuer())
                .audience(List.of(authTokenProperties.audience()))
                .subject(userId.toString())
                .claim("role", role.name())
                .id(jwtId)
                .claim("typ", ACCESS_TOKEN_TYPE)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        String tokenValue =jwtEncoder
                .encode(
                        JwtEncoderParameters.from(
                                header,
                                claims
                        )
                ).getTokenValue();

        return new IssuedAccessToken(
                tokenValue,
                issuedAt,
                expiresAt
        );
    }
}
