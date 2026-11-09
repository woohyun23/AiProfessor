package com.aiprofessor.backend.auth.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import com.aiprofessor.backend.auth.domain.UserRole;
import com.aiprofessor.backend.auth.dto.request.LoginRequest;
import com.aiprofessor.backend.auth.dto.response.LoginResponse;
import com.aiprofessor.backend.auth.dto.response.LoginUserResponse;
import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.auth.service.LoginResult;
import com.aiprofessor.backend.auth.service.LoginService;
import com.aiprofessor.backend.support.error.ApiException;
import com.aiprofessor.backend.support.error.GlobalExceptionHandler;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private static final String LOGIN_PATH =
            "/api/v1/auth/login";
    private static final String CLIENT_IP = "203.0.113.10";
    private static final String USER_AGENT = "test-browser";

    @Mock
    private LoginService loginService;

    @Mock
    private Environment environment;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        AuthTokenProperties authTokenProperties =
                new AuthTokenProperties(
                        "aiprofessor",
                        "aiprofessor-api",
                        "test-key",
                        Duration.ofMinutes(30),
                        Duration.ofDays(14),
                        32
                );
        AuthController authController = new AuthController(
                loginService,
                authTokenProperties,
                environment
        );

        mockMvc = MockMvcBuilders
                .standaloneSetup(authController)
                .setControllerAdvice(
                        new GlobalExceptionHandler()
                )
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void 로그인하면_Access_Token과_로컬용_Refresh_Token_Cookie를_반환한다()
            throws Exception {
        LoginRequest request = new LoginRequest(
                "learner@example.com",
                "password123"
        );
        LoginResult result = createLoginResult();
        given(loginService.login(
                request,
                CLIENT_IP,
                USER_AGENT
        )).willReturn(result);
        given(environment.matchesProfiles("prod"))
                .willReturn(false);

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.USER_AGENT, USER_AGENT)
                        .with(servletRequest -> {
                            servletRequest.setRemoteAddr(CLIENT_IP);
                            return servletRequest;
                        })
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken")
                        .value("access-token"))
                .andExpect(jsonPath("$.data.tokenType")
                        .value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn")
                        .value(1_800))
                .andExpect(jsonPath("$.data.user.email")
                        .value("learner@example.com"))
                .andExpect(jsonPath("$.data.user.role")
                        .value("USER"))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString(
                                        "refresh_token=rft_test-token"
                                ),
                                containsString("Path=/api/v1/auth"),
                                containsString("Max-Age=1209600"),
                                containsString("HttpOnly"),
                                containsString("SameSite=Lax"),
                                not(containsString("Secure"))
                        )
                ));

        verify(loginService).login(
                request,
                CLIENT_IP,
                USER_AGENT
        );
    }

    @Test
    void 운영_환경의_Refresh_Token_Cookie에는_Secure를_적용한다()
            throws Exception {
        LoginRequest request = new LoginRequest(
                "learner@example.com",
                "password123"
        );
        given(loginService.login(
                any(LoginRequest.class),
                any(String.class),
                any(String.class)
        )).willReturn(createLoginResult());
        given(environment.matchesProfiles("prod"))
                .willReturn(true);

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.USER_AGENT, USER_AGENT)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        containsString("Secure")
                ));
    }

    @Test
    void 잘못된_자격_증명이면_동일한_인증_오류를_반환한다()
            throws Exception {
        LoginRequest request = new LoginRequest(
                "learner@example.com",
                "wrong-password"
        );
        given(loginService.login(
                any(LoginRequest.class),
                any(String.class),
                any(String.class)
        )).willThrow(
                new ApiException(
                        AuthErrorCode.INVALID_CREDENTIALS
                )
        );

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.USER_AGENT, USER_AGENT)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode")
                        .value("AUTH_0001"))
                .andExpect(header().doesNotExist(
                        HttpHeaders.SET_COOKIE
                ));
    }

    @Test
    void 로그인_요청값이_유효하지_않으면_서비스를_호출하지_않는다()
            throws Exception {
        LoginRequest invalidRequest = new LoginRequest(
                "invalid-email",
                ""
        );

        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                objectMapper.writeValueAsBytes(
                                        invalidRequest
                                )
                        ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode")
                        .value("COMMON_0001"));

        verify(loginService, never()).login(
                any(),
                any(),
                any()
        );
    }

    private LoginResult createLoginResult() {
        LoginUserResponse userResponse =
                new LoginUserResponse(
                        UUID.fromString(
                                "0a68688f-013d-4622-8cba-e3e7d31316df"
                        ),
                        "learner@example.com",
                        "학습자",
                        UserRole.USER
                );
        LoginResponse loginResponse = new LoginResponse(
                "access-token",
                "Bearer",
                1_800,
                userResponse
        );

        return new LoginResult(
                loginResponse,
                "rft_test-token"
        );
    }
}
