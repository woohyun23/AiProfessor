package com.aiprofessor.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JsonAccessDeniedHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonAccessDeniedHandler accessDeniedHandler =
            new JsonAccessDeniedHandler(
                    new SecurityErrorResponseWriter(objectMapper)
            );

    @Test
    void 인증된_사용자의_권한이_부족하면_AUTH_0009를_반환한다()
            throws Exception {
        JwtAuthenticationToken authentication =
                new JwtAuthenticationToken(
                        createJwt(),
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );
        MockHttpServletRequest request =
                new MockHttpServletRequest();
        request.setUserPrincipal(authentication);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        accessDeniedHandler.handle(
                request,
                response,
                new AccessDeniedException("forbidden")
        );

        JsonNode body = objectMapper.readTree(
                response.getContentAsByteArray()
        );

        assertThat(response.getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .contains("Bearer")
                .contains("insufficient_scope");
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("errorCode").stringValue())
                .isEqualTo("AUTH_0009");
        assertThat(body.get("message").stringValue())
                .isEqualTo("요청한 기능에 접근할 권한이 없습니다.");
        assertThat(body.get("errors").isEmpty()).isTrue();
    }

    private Jwt createJwt() {
        Instant issuedAt =
                Instant.parse("2026-11-15T00:00:00Z");

        return Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .subject("0a68688f-013d-4622-8cba-e3e7d31316df")
                .claim("role", "USER")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofMinutes(30)))
                .build();
    }
}
