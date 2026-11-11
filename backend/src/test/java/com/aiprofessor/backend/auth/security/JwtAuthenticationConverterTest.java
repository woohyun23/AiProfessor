package com.aiprofessor.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiprofessor.backend.auth.config.JwtAuthenticationConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JwtAuthenticationConverterTest {

    private static final UUID USER_ID = UUID.fromString(
            "0a68688f-013d-4622-8cba-e3e7d31316df"
    );
    private static final Instant ISSUED_AT =
            Instant.parse("2026-11-11T00:00:00Z");

    private JwtAuthenticationConverter converter;

    @BeforeEach
    void setUp() {
        JwtAuthenticationConfig config =
                new JwtAuthenticationConfig();

        converter = config.jwtAuthenticationConverter();
    }

    @Test
    void subject를_인증_사용자_식별자로_변환한다() {
        Jwt jwt = createJwt("USER");

        AbstractAuthenticationToken authentication =
                converter.convert(jwt);

        assertThat(authentication)
                .isInstanceOf(JwtAuthenticationToken.class);
        assertThat(authentication.getName())
                .isEqualTo(USER_ID.toString());
        assertThat(authentication.isAuthenticated()).isTrue();
    }

    @Test
    void USER_role을_ROLE_USER_권한으로_변환한다() {
        Jwt jwt = createJwt("USER");

        AbstractAuthenticationToken authentication =
                converter.convert(jwt);

        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_USER");
    }

    @Test
    void ADMIN_role을_ROLE_ADMIN_권한으로_변환한다() {
        Jwt jwt = createJwt("ADMIN");

        AbstractAuthenticationToken authentication =
                converter.convert(jwt);

        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ADMIN");
    }

    private Jwt createJwt(String role) {
        return Jwt.withTokenValue("access-token")
                .header("alg", "RS256")
                .issuer("aiprofessor")
                .audience(List.of("aiprofessor-api"))
                .subject(USER_ID.toString())
                .claim("role", role)
                .claim("typ", "access")
                .claim(
                        "jti",
                        "21a2d59d-2d9f-49ab-9014-bdb0b6de12dd"
                )
                .issuedAt(ISSUED_AT)
                .expiresAt(ISSUED_AT.plus(Duration.ofMinutes(30)))
                .build();
    }
}
