package com.aiprofessor.backend.auth.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import com.aiprofessor.backend.auth.domain.UserRole;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class AccessTokenGeneratorTest {

    private static final Instant ISSUED_AT =
            Instant.parse("2026-11-09T06:00:00Z");

    @Test
    void RS256으로_서명하고_필수_Claim을_포함한_Access_Token을_발급한다()
            throws Exception {
        KeyPairGenerator keyPairGenerator =
                KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2_048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        RSAPublicKey publicKey =
                (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey =
                (RSAPrivateKey) keyPair.getPrivate();
        AuthTokenProperties properties =
                new AuthTokenProperties(
                        "aiprofessor",
                        "aiprofessor-api",
                        "test-key-v1",
                        Duration.ofMinutes(30),
                        Duration.ofDays(14),
                        32
                );
        NimbusJwtEncoder jwtEncoder = NimbusJwtEncoder
                .withKeyPair(publicKey, privateKey)
                .algorithm(SignatureAlgorithm.RS256)
                .jwkPostProcessor(builder ->
                        builder.keyID(properties.keyId())
                )
                .build();
        AccessTokenGenerator generator =
                new AccessTokenGenerator(
                        jwtEncoder,
                        properties,
                        Clock.fixed(ISSUED_AT, ZoneOffset.UTC)
                );
        UUID userId = UUID.randomUUID();

        IssuedAccessToken issuedToken = generator.generate(
                userId,
                UserRole.USER
        );

        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder
                .withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        jwtDecoder.setJwtValidator(token ->
                OAuth2TokenValidatorResult.success()
        );
        Jwt jwt = jwtDecoder.decode(
                issuedToken.tokenValue()
        );

        assertThat(jwt.getHeaders())
                .containsEntry("alg", "RS256")
                .containsEntry("typ", "JWT")
                .containsEntry("kid", "test-key-v1");
        assertThat(jwt.getClaimAsString("iss"))
                .isEqualTo("aiprofessor");
        assertThat(jwt.getAudience())
                .containsExactly("aiprofessor-api");
        assertThat(jwt.getSubject())
                .isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("role"))
                .isEqualTo("USER");
        assertThat(jwt.getClaimAsString("typ"))
                .isEqualTo("access");
        assertThatCodeIsUuid(jwt.getId());
        assertThat(jwt.getIssuedAt())
                .isEqualTo(ISSUED_AT);
        assertThat(jwt.getExpiresAt())
                .isEqualTo(ISSUED_AT.plus(Duration.ofMinutes(30)));
        assertThat(issuedToken.issuedAt())
                .isEqualTo(ISSUED_AT);
        assertThat(issuedToken.expiresAt())
                .isEqualTo(ISSUED_AT.plus(Duration.ofMinutes(30)));
        assertThat(issuedToken.expiresInSeconds())
                .isEqualTo(1_800);
    }

    private void assertThatCodeIsUuid(String value) {
        assertThat(value).isNotBlank();
        assertThat(UUID.fromString(value)).isNotNull();
    }
}
