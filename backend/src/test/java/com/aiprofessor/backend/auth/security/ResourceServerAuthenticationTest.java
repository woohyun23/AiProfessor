package com.aiprofessor.backend.auth.security;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiprofessor.backend.auth.config.JwtAuthenticationConfig;
import com.aiprofessor.backend.auth.config.SecurityConfig;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.domain.UserStatus;
import com.aiprofessor.backend.auth.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(ResourceServerAuthenticationTest.TestController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationConfig.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        CurrentAccountStatusValidator.class,
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

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void Access_Token이_없으면_보호_API_요청을_거부한다()
            throws Exception {
        mockMvc.perform(get(PROTECTED_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode")
                        .value("AUTH_0008"))
                .andExpect(jsonPath("$.message")
                        .value("인증이 필요한 요청입니다."))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        containsString("Bearer")
                ));

        verifyNoInteractions(jwtDecoder);
        verifyNoInteractions(userRepository);
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
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode")
                        .value("AUTH_0002"))
                .andExpect(jsonPath("$.message")
                        .value("유효하지 않은 Access Token입니다."))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        containsString("invalid_token")
                ));

        verify(jwtDecoder).decode("invalid-token");
        verifyNoInteractions(userRepository);
    }

    @Test
    void 만료된_Access_Token이면_AUTH_0003을_반환한다()
            throws Exception {
        OAuth2Error expiredError = new OAuth2Error(
                "invalid_token",
                "Jwt expired at 2026-11-11T00:30:00Z",
                null
        );
        given(jwtDecoder.decode("expired-token"))
                .willThrow(new JwtValidationException(
                        "Jwt validation failed",
                        List.of(expiredError)
                ));

        mockMvc.perform(get(PROTECTED_PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer expired-token"
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode")
                        .value("AUTH_0003"))
                .andExpect(jsonPath("$.message")
                        .value("만료된 Access Token입니다."))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        containsString("invalid_token")
                ));

        verify(jwtDecoder).decode("expired-token");
        verifyNoInteractions(userRepository);
    }

    @Test
    void 유효한_Access_Token이면_인증_사용자와_권한을_전달한다()
            throws Exception {
        given(jwtDecoder.decode("valid-token"))
                .willReturn(createJwt("USER"));
        given(userRepository.findById(USER_ID))
                .willReturn(Optional.of(createActiveUser()));

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
    void 잠긴_계정이면_AUTH_0007을_반환한다()
            throws Exception {
        given(jwtDecoder.decode("locked-user-token"))
                .willReturn(createJwt("USER"));
        given(userRepository.findById(USER_ID))
                .willReturn(Optional.of(
                        createUser(UserStatus.LOCKED)
                ));

        mockMvc.perform(get(PROTECTED_PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer locked-user-token"
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode")
                        .value("AUTH_0007"))
                .andExpect(jsonPath("$.message")
                        .value("잠금 처리된 계정입니다."))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void 탈퇴한_계정이면_USER_0002를_반환한다()
            throws Exception {
        given(jwtDecoder.decode("withdrawn-user-token"))
                .willReturn(createJwt("USER"));
        given(userRepository.findById(USER_ID))
                .willReturn(Optional.of(
                        createUser(UserStatus.WITHDRAWN)
                ));

        mockMvc.perform(get(PROTECTED_PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer withdrawn-user-token"
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode")
                        .value("USER_0002"))
                .andExpect(jsonPath("$.message")
                        .value("탈퇴 처리된 계정입니다."))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void 토큰의_사용자가_존재하지_않으면_AUTH_0002를_반환한다()
            throws Exception {
        given(jwtDecoder.decode("missing-user-token"))
                .willReturn(createJwt("USER"));
        given(userRepository.findById(USER_ID))
                .willReturn(Optional.empty());

        mockMvc.perform(get(PROTECTED_PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer missing-user-token"
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode")
                        .value("AUTH_0002"))
                .andExpect(jsonPath("$.message")
                        .value("유효하지 않은 Access Token입니다."))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void 로그인_API는_Access_Token이_있어도_계정_상태를_조회하지_않는다()
            throws Exception {
        given(jwtDecoder.decode("public-api-token"))
                .willReturn(createJwt("USER"));

        mockMvc.perform(post(LOGIN_PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer public-api-token"
                        ))
                .andExpect(status().isNoContent());

        verify(jwtDecoder).decode("public-api-token");
        verifyNoInteractions(userRepository);
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

    private User createActiveUser() {
        return createUser(UserStatus.ACTIVE);
    }

    private User createUser(UserStatus status) {
        User user = User.create(
                "learner@example.com",
                "{bcrypt}password-hash",
                "학습자"
        );
        ReflectionTestUtils.setField(user, "status", status);

        return user;
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
