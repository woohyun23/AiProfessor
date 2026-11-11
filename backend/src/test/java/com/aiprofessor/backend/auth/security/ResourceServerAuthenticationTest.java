package com.aiprofessor.backend.auth.security;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiprofessor.backend.auth.config.JwtAuthenticationConfig;
import com.aiprofessor.backend.auth.config.SecurityConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(ResourceServerAuthenticationTest.TestController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationConfig.class,
        ResourceServerAuthenticationTest.TestController.class
})
class ResourceServerAuthenticationTest {

    private static final String PROTECTED_PATH = "/test/protected";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final UUID USER_ID = UUID.fromString(
            "0a68688f-013d-4622-8cba-e3e7d31316df"
    );
    private static final Instant ISSUED_AT =
            Instant.parse("2026-11-11T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void Access_Token이_없으면_보호_API_요청을_거부한다()
            throws Exception {
        mockMvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void 유효하지_않은_Access_Token이면_보호_API_요청을_거부한다()
            throws Exception {
        given(jwtDecoder.decode("invalid-token"))
                .willThrow(new BadJwtException("invalid token"));

        mockMvc.perform(get(PROTECTED_PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer invalid-token"
                        ))
                .andExpect(status().isUnauthorized());

        verify(jwtDecoder).decode("invalid-token");
    }

    @Test
    void 유효한_Access_Token이면_인증_사용자와_권한을_전달한다()
            throws Exception {
        given(jwtDecoder.decode("valid-token"))
                .willReturn(createJwt("USER"));

        mockMvc.perform(get(PROTECTED_PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer valid-token"
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name")
                        .value(USER_ID.toString()))
                .andExpect(jsonPath("$.authorities")
                        .value(hasItem("ROLE_USER")));

        verify(jwtDecoder).decode("valid-token");
    }

    @Test
    void 로그인_API는_Access_Token_없이_접근할_수_있다()
            throws Exception {
        mockMvc.perform(post(LOGIN_PATH))
                .andExpect(status().isNoContent());

        verifyNoInteractions(jwtDecoder);
    }

    private Jwt createJwt(String role) {
        return Jwt.withTokenValue("valid-token")
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

    @RestController
    static class TestController {

        @GetMapping(PROTECTED_PATH)
        Map<String, Object> protectedEndpoint(
                Authentication authentication
        ) {
            List<String> authorities = authentication
                    .getAuthorities()
                    .stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();

            return Map.of(
                    "name", authentication.getName(),
                    "authorities", authorities
            );
        }

        @PostMapping(LOGIN_PATH)
        ResponseEntity<Void> login() {
            return ResponseEntity.noContent().build();
        }
    }
}
