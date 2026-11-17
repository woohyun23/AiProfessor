package com.aiprofessor.backend.auth.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiprofessor.backend.auth.config.JwtAuthenticationConfig;
import com.aiprofessor.backend.auth.config.SecurityConfig;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.dto.request.ChangePasswordRequest;
import com.aiprofessor.backend.auth.error.UserErrorCode;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.auth.security.CurrentAccountStatusValidator;
import com.aiprofessor.backend.auth.security.JsonAccessDeniedHandler;
import com.aiprofessor.backend.auth.security.JsonAuthenticationEntryPoint;
import com.aiprofessor.backend.auth.security.SecurityErrorResponseWriter;
import com.aiprofessor.backend.auth.service.MyProfileService;
import com.aiprofessor.backend.auth.service.PasswordChangeService;
import com.aiprofessor.backend.auth.service.SignUpService;
import com.aiprofessor.backend.support.error.ApiException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(UserController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationConfig.class,
        CurrentAccountStatusValidator.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        MyProfileService.class
})
class MyProfileControllerTest {

    private static final String PATH = "/api/v1/users/me";
    private static final String PASSWORD_PATH = PATH + "/password";
    private static final String TOKEN = "test-access-token";
    private static final String BEARER = "Bearer " + TOKEN;
    private static final Instant CREATED_AT = Instant.parse("2026-11-17T00:00:00Z");
    private static final ChangePasswordRequest PASSWORD_CHANGE =
            new ChangePasswordRequest("old-password", "new-password");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SignUpService signUpService;

    @MockitoBean
    private PasswordChangeService passwordChangeService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void 요청의_사용자_ID와_관계없이_JWT_sub의_본인_정보를_반환한다() throws Exception {
        User user = authenticateLearner();

        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .queryParam("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.email").value("learner@example.com"))
                .andExpect(jsonPath("$.data.displayName").value("학습자"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.data.passwordHash").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.tokenHash").doesNotHaveJsonPath());
    }

    @Test
    void 유효한_Bearer_토큰으로_표시_이름을_변경하고_재조회할_수_있다() throws Exception {
        User user = authenticateLearner();

        mockMvc.perform(patch(PATH)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .queryParam("userId", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"백엔드 학습자"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.displayName").value("백엔드 학습자"));

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("백엔드 학습자"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"displayName\":\"  \"}",
            "{\"displayName\":\"가\"}",
            "{\"displayName\":\"가나다라마바사아자차카\"}"
    })
    void 유효하지_않은_표시_이름은_거부하고_기존_이름을_유지한다(String requestBody)
            throws Exception {
        authenticateLearner();

        mockMvc.perform(patch(PATH)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_0001"))
                .andExpect(jsonPath("$.errors[0].field").value("displayName"));

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("학습자"));
    }

    @Test
    void Access_Token_없이는_본인_정보를_조회하거나_변경할_수_없다() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_0008"));

        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"백엔드 학습자"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_0008"));

        verifyNoInteractions(userRepository);
    }

    @Test
    void 비밀번호_변경은_인증된_사용자에게_본문_없는_204를_반환한다() throws Exception {
        User user = authenticateLearner();

        mockMvc.perform(changePasswordRequest()
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .queryParam("userId", UUID.randomUUID().toString()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(passwordChangeService).changePassword(user.getId(), PASSWORD_CHANGE);
    }

    @Test
    void Access_Token이_없으면_비밀번호를_변경할_수_없다() throws Exception {
        mockMvc.perform(changePasswordRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_0008"));

        verifyNoInteractions(passwordChangeService);
    }

    @Test
    void 짧은_새_비밀번호는_서비스_호출_전에_거부된다() throws Exception {
        authenticateLearner();

        mockMvc.perform(patch(PASSWORD_PATH)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-password","newPassword":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_0001"))
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));

        verifyNoInteractions(passwordChangeService);
    }

    @ParameterizedTest
    @CsvSource({
            "CURRENT_PASSWORD_MISMATCH, USER_0003",
            "SAME_PASSWORD, USER_0004"
    })
    void 비밀번호_변경의_실패_원인별_오류_코드를_반환한다(
            UserErrorCode errorCode,
            String expectedCode
    ) throws Exception {
        User user = authenticateLearner();
        willThrow(new ApiException(errorCode))
                .given(passwordChangeService).changePassword(user.getId(), PASSWORD_CHANGE);

        mockMvc.perform(changePasswordRequest().header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(expectedCode));
    }

    private MockHttpServletRequestBuilder changePasswordRequest() {
        return patch(PASSWORD_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword":"old-password","newPassword":"new-password"}
                        """);
    }

    private User authenticateLearner() {
        User user = User.create("learner@example.com", "{bcrypt}test-password-hash", "학습자");
        ReflectionTestUtils.setField(user, "createdAt", CREATED_AT);

        Jwt jwt = Jwt.withTokenValue(TOKEN)
                .header("alg", "RS256")
                .subject(user.getId().toString())
                .claim("role", "USER")
                .build();
        given(jwtDecoder.decode(TOKEN)).willReturn(jwt);
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        return user;
    }
}
