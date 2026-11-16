package com.aiprofessor.backend.auth.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import com.aiprofessor.backend.auth.config.JwtAuthenticationConfig;
import com.aiprofessor.backend.auth.config.RefreshSecurityConfig;
import com.aiprofessor.backend.auth.config.SecurityConfig;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.auth.security.CurrentAccountStatusValidator;
import com.aiprofessor.backend.auth.security.JsonAccessDeniedHandler;
import com.aiprofessor.backend.auth.security.JsonAuthenticationEntryPoint;
import com.aiprofessor.backend.auth.security.SecurityErrorResponseWriter;
import com.aiprofessor.backend.auth.service.LoginService;
import com.aiprofessor.backend.auth.service.LogoutService;
import com.aiprofessor.backend.auth.service.RefreshTokenRotationService;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(
        controllers = AuthController.class,
        properties = "aiprofessor.auth.logout.allowed-origins=https://app.example.test"
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
class LogoutControllerTest {

    private static final String PATH = "/api/v1/auth/logout";
    private static final String ORIGIN = "https://app.example.test";
    private static final String ACCESS_TOKEN = "test-access-token";
    private static final String REFRESH_TOKEN = "rft_test-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoginService loginService;

    @MockitoBean
    private LogoutService logoutService;

    @MockitoBean
    private RefreshTokenRotationService rotationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private UserRepository userRepository;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = REFRESH_TOKEN)
    void 인증된_사용자는_로그아웃하고_Cookie를_만료시킨다(String rawRefreshToken)
            throws Exception {
        User user = User.create("learner@example.com", "{bcrypt}password-hash", "학습자");
        Jwt jwt = Jwt.withTokenValue(ACCESS_TOKEN)
                .header("alg", "RS256")
                .subject(user.getId().toString())
                .claim("role", "USER")
                .build();
        given(jwtDecoder.decode(ACCESS_TOKEN)).willReturn(jwt);
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));

        MockHttpServletRequestBuilder request = post(PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN);
        if (rawRefreshToken != null) {
            request.cookie(new Cookie("refresh_token", rawRefreshToken));
        }

        mockMvc.perform(request)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("refresh_token=;"),
                        containsString("Path=/api/v1/auth"),
                        containsString("Max-Age=0"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Lax")
                )));

        verify(logoutService).logout(user.getId(), rawRefreshToken);
    }

    @Test
    void Access_Token이_없으면_로그아웃을_거부한다() throws Exception {
        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie("refresh_token", REFRESH_TOKEN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_0008"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verifyNoInteractions(logoutService);
    }

    @Test
    void 유효하지_않은_Access_Token으로는_로그아웃할_수_없다() throws Exception {
        given(jwtDecoder.decode(ACCESS_TOKEN)).willThrow(new BadJwtException("invalid token"));

        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_0002"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verifyNoInteractions(logoutService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "https://other.example.test"})
    void Origin이_허용되지_않으면_인증과_로그아웃을_시작하지_않는다(String origin)
            throws Exception {
        MockHttpServletRequestBuilder request = post(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN);
        if (origin != null) {
            request.header(HttpHeaders.ORIGIN, origin);
        }

        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH_0009"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verifyNoInteractions(jwtDecoder, logoutService);
    }

    @Test
    void 허용된_Origin의_사전_요청은_Bearer_토큰_없이_처리된다() throws Exception {
        mockMvc.perform(options(PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verifyNoInteractions(jwtDecoder, logoutService);
    }
}
