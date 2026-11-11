package com.aiprofessor.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import com.aiprofessor.backend.auth.config.JwtKeyConfig;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwsHeader;

class AccessTokenDecoderTest {

    private static final Instant NOW =
            Instant.parse("2026-11-10T06:00:00Z");
    private static final UUID USER_ID = UUID.fromString(
            "0a68688f-013d-4622-8cba-e3e7d31316df"
    );
    private static final String JWT_ID =
            "21a2d59d-2d9f-49ab-9014-bdb0b6de12dd";

    private AuthTokenProperties properties;
    private KeyPair signingKeyPair;
    private JwtEncoder jwtEncoder;
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AuthTokenProperties(
                "aiprofessor",
                "aiprofessor-api",
                "test-key-v1",
                Duration.ofMinutes(30),
                Duration.ofDays(14),
                32
        );
        signingKeyPair = generateKeyPair();
        JwtKeyConfig jwtKeyConfig = new JwtKeyConfig();
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

        jwtEncoder = jwtKeyConfig.jwtEncoder(
                signingKeyPair,
                properties
        );
        jwtDecoder = jwtKeyConfig.jwtDecoder(
                signingKeyPair,
                properties,
                fixedClock
        );
    }

    @Test
    void 정상_Access_Token을_검증한다() {
        Jwt jwt = jwtDecoder.decode(createValidToken());

        assertThat(jwt.getClaimAsString("iss"))
                .isEqualTo("aiprofessor");
        assertThat(jwt.getAudience())
                .containsExactly("aiprofessor-api");
        assertThat(jwt.getSubject())
                .isEqualTo(USER_ID.toString());
        assertThat(jwt.getClaimAsString("role"))
                .isEqualTo("USER");
        assertThat(jwt.getClaimAsString("typ"))
                .isEqualTo("access");
        assertThat(jwt.getId()).isEqualTo(JWT_ID);
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    void 다른_RSA_개인키로_서명한_토큰을_거부한다()
            throws Exception {
        JwtEncoder differentEncoder = new JwtKeyConfig()
                .jwtEncoder(generateKeyPair(), properties);
        String token = encode(
                differentEncoder,
                validClaimsBuilder()
        );

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void Payload가_변조된_토큰을_거부한다() {
        String tamperedToken = tamperRole(createValidToken());

        assertThatThrownBy(() ->
                jwtDecoder.decode(tamperedToken)
        ).isInstanceOf(JwtException.class);
    }

    @Test
    void 허용된_60초보다_더_오래전에_만료된_토큰을_거부한다() {
        String token = encode(
                jwtEncoder,
                validClaimsBuilder()
                        .issuedAt(NOW.minus(Duration.ofMinutes(30)))
                        .expiresAt(NOW.minusSeconds(61))
        );

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void 만료_시각이_없는_토큰을_거부한다() {
        JwtClaimsSet.Builder claims = baseClaimsBuilder()
                .issuedAt(NOW);
        String token = encode(jwtEncoder, claims);

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void 잘못된_issuer를_거부한다() {
        String token = encode(
                jwtEncoder,
                validClaimsBuilder().issuer("other-issuer")
        );

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void 잘못된_audience를_거부한다() {
        String token = encode(
                jwtEncoder,
                validClaimsBuilder().audience(
                        List.of("other-api")
                )
        );

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void access가_아닌_토큰_타입을_표준_invalid_token으로_거부한다() {
        String token = encode(
                jwtEncoder,
                validClaimsBuilder().claim("typ", "refresh")
        );

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOfSatisfying(
                        JwtValidationException.class,
                        exception -> assertThat(exception.getErrors())
                                .extracting(OAuth2Error::getErrorCode)
                                .contains("invalid_token")
                );
    }

    @Test
    void UUID가_아닌_subject를_거부한다() {
        String token = encode(
                jwtEncoder,
                validClaimsBuilder().subject("invalid-user-id")
        );

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void 허용되지_않은_role을_거부한다() {
        String token = encode(
                jwtEncoder,
                validClaimsBuilder().claim("role", "SUPER_ADMIN")
        );

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void role이_없는_토큰을_표준_invalid_token으로_거부한다() {
        JwtClaimsSet.Builder claims = validClaimsBuilder()
                .claims(claimValues ->
                        claimValues.remove("role")
                );
        String token = encode(jwtEncoder, claims);

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOfSatisfying(
                        JwtValidationException.class,
                        exception -> assertThat(exception.getErrors())
                                .extracting(OAuth2Error::getErrorCode)
                                .contains("invalid_token")
                );
    }

    @Test
    void UUID가_아닌_jti를_거부한다() {
        String token = encode(
                jwtEncoder,
                validClaimsBuilder().id("invalid-token-id")
        );

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void 발급_시각이_없는_토큰을_거부한다() {
        JwtClaimsSet.Builder claims = baseClaimsBuilder()
                .expiresAt(NOW.plus(Duration.ofMinutes(30)));
        String token = encode(jwtEncoder, claims);

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void 발급_시각이_허용된_60초보다_더_미래이면_거부한다() {
        String token = encode(
                jwtEncoder,
                validClaimsBuilder().issuedAt(NOW.plusSeconds(61))
        );

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    private String createValidToken() {
        return encode(jwtEncoder, validClaimsBuilder());
    }

    private JwtClaimsSet.Builder validClaimsBuilder() {
        return baseClaimsBuilder()
                .issuedAt(NOW)
                .expiresAt(NOW.plus(Duration.ofMinutes(30)));
    }

    private JwtClaimsSet.Builder baseClaimsBuilder() {
        return JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(USER_ID.toString())
                .claim("role", "USER")
                .id(JWT_ID)
                .claim("typ", "access");
    }

    private String encode(
            JwtEncoder encoder,
            JwtClaimsSet.Builder claimsBuilder
    ) {
        JwsHeader header = JwsHeader
                .with(SignatureAlgorithm.RS256)
                .type("JWT")
                .keyId(properties.keyId())
                .build();

        return encoder.encode(
                JwtEncoderParameters.from(
                        header,
                        claimsBuilder.build()
                )
        ).getTokenValue();
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator =
                KeyPairGenerator.getInstance("RSA");
        generator.initialize(2_048);
        return generator.generateKeyPair();
    }

    private String tamperRole(String token) {
        String[] parts = token.split("\\.");
        String payload = new String(
                Base64.getUrlDecoder().decode(parts[1]),
                StandardCharsets.UTF_8
        );
        String tamperedPayload = payload.replace(
                "\"role\":\"USER\"",
                "\"role\":\"ADMIN\""
        );

        assertThat(tamperedPayload).isNotEqualTo(payload);

        parts[1] = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        tamperedPayload.getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        return String.join(".", parts);
    }
}
