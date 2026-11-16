package com.aiprofessor.backend.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import com.aiprofessor.backend.auth.config.JwtAuthenticationConfig;
import com.aiprofessor.backend.auth.config.RefreshSecurityConfig;
import com.aiprofessor.backend.auth.config.SecurityConfig;
import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.auth.security.CurrentAccountStatusValidator;
import com.aiprofessor.backend.auth.security.JsonAccessDeniedHandler;
import com.aiprofessor.backend.auth.security.JsonAuthenticationEntryPoint;
import com.aiprofessor.backend.auth.security.SecurityErrorResponseWriter;
import com.aiprofessor.backend.auth.service.LoginService;
import com.aiprofessor.backend.auth.service.RefreshRotationResult;
import com.aiprofessor.backend.auth.service.RefreshTokenRotationService;
import com.aiprofessor.backend.auth.token.IssuedAccessToken;
import com.aiprofessor.backend.support.error.ApiException;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(
        controllers = AuthController.class,
        properties = "aiprofessor.auth.refresh.allowed-origins=https://app.example.test"
)
@EnableConfigurationProperties(AuthTokenProperties.class)
@Import({
        RefreshSecurityConfig.class,
        SecurityConfig.class,
        JwtAuthenticationConfig.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        CurrentAccountStatusValidator.class,
        SecurityErrorResponseWriter.class
})
@ActiveProfiles("local")
class RefreshControllerTest {

    private static final String PATH = "/api/v1/auth/refresh";
    private static final String ORIGIN = "https://app.example.test";
    private static final String OLD_TOKEN = "rft_old-token";
    private static final String NEW_TOKEN = "rft_new-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoginService loginService;

    @MockitoBean
    private RefreshTokenRotationService rotationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private UserRepository userRepository;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "Bearer no-longer-valid")
    void 갱신_요청은_Bearer_인증_없이_Cookie를_회전한다(String authorization)
            throws Exception {
        Instant issuedAt = Instant.parse("2026-11-16T00:00:00Z");
        given(rotationService.rotate(OLD_TOKEN)).willReturn(new RefreshRotationResult(
                new IssuedAccessToken("new-access-token", issuedAt, issuedAt.plusSeconds(1800)),
                NEW_TOKEN
        ));

        MockHttpServletRequestBuilder request = refreshRequest();
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=" + NEW_TOKEN)))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=1209600")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("Domain="))))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("Secure"))))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));

        verifyNoInteractions(jwtDecoder);
    }

    @ParameterizedTest
    @EnumSource(value = AuthErrorCode.class, names = {
            "INVALID_REFRESH_TOKEN", "REFRESH_TOKEN_EXPIRED", "REFRESH_TOKEN_REUSED"
    })
    void 토큰_오류를_반환하고_기존_Cookie를_만료시킨다(AuthErrorCode errorCode)
            throws Exception {
        given(rotationService.rotate(OLD_TOKEN)).willThrow(new ApiException(errorCode));

        mockMvc.perform(refreshRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value(errorCode.code()))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=;")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @Test
    void Cookie가_없으면_AUTH_0004를_반환한다() throws Exception {
        given(rotationService.rotate(null))
                .willThrow(new ApiException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        mockMvc.perform(post(PATH).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_0004"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"null", "https://other.example.test"})
    void 허용되지_않거나_누락된_Origin에서는_회전을_시작하지_않는다(String origin)
            throws Exception {
        MockHttpServletRequestBuilder request = post(PATH).cookie(new Cookie("refresh_token", OLD_TOKEN));
        if (origin != null) {
            request.header(HttpHeaders.ORIGIN, origin);
        }

        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH_0009"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verifyNoInteractions(rotationService);
    }

    @Test
    void 허용된_Origin의_preflight에는_회전_없이_CORS_헤더를_반환한다() throws Exception {
        mockMvc.perform(options(PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));

        verifyNoInteractions(rotationService);
    }

    private MockHttpServletRequestBuilder refreshRequest() {
        return post(PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(new Cookie("refresh_token", OLD_TOKEN));
    }
}
