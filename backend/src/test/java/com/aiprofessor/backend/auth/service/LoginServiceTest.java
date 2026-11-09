package com.aiprofessor.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import com.aiprofessor.backend.auth.domain.EmailNormalizer;
import com.aiprofessor.backend.auth.domain.RefreshToken;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.domain.UserStatus;
import com.aiprofessor.backend.auth.dto.request.LoginRequest;
import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.auth.error.UserErrorCode;
import com.aiprofessor.backend.auth.repository.RefreshTokenRepository;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.auth.security.ClientIpHasher;
import com.aiprofessor.backend.auth.token.AccessTokenGenerator;
import com.aiprofessor.backend.auth.token.IssuedAccessToken;
import com.aiprofessor.backend.auth.token.RefreshTokenGenerator;
import com.aiprofessor.backend.auth.token.RefreshTokenHasher;
import com.aiprofessor.backend.support.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    private static final String NORMALIZED_EMAIL =
            "learner@example.com";
    private static final String RAW_PASSWORD = "password123";
    private static final String STORED_PASSWORD_HASH =
            "{bcrypt}stored-password-hash";
    private static final String RAW_REFRESH_TOKEN =
            "rft_test-refresh-token";
    private static final String REFRESH_TOKEN_HASH = "a".repeat(64);
    private static final String CLIENT_IP = "203.0.113.10";
    private static final String CLIENT_IP_HASH = "b".repeat(64);
    private static final Instant ISSUED_AT =
            Instant.parse("2026-11-09T06:00:00Z");
    private static final Instant ACCESS_TOKEN_EXPIRES_AT =
            ISSUED_AT.plus(Duration.ofMinutes(30));

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AccessTokenGenerator accessTokenGenerator;

    @Mock
    private RefreshTokenGenerator refreshTokenGenerator;

    @Mock
    private RefreshTokenHasher refreshTokenHasher;

    @Mock
    private ClientIpHasher clientIpHasher;

    private LoginService loginService;

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

        loginService = new LoginService(
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                new EmailNormalizer(),
                accessTokenGenerator,
                refreshTokenGenerator,
                refreshTokenHasher,
                clientIpHasher,
                authTokenProperties
        );
    }

    @Test
    void 이메일을_정규화하고_토큰을_발급해_로그인한다() {
        User user = createUser();
        LoginRequest request = new LoginRequest(
                "  Learner@Example.COM  ",
                RAW_PASSWORD
        );
        IssuedAccessToken issuedAccessToken =
                new IssuedAccessToken(
                        "access-token",
                        ISSUED_AT,
                        ACCESS_TOKEN_EXPIRES_AT
                );
        String longUserAgent = "😀".repeat(501);

        given(userRepository.findByEmail(NORMALIZED_EMAIL))
                .willReturn(Optional.of(user));
        given(passwordEncoder.matches(
                RAW_PASSWORD,
                STORED_PASSWORD_HASH
        )).willReturn(true);
        given(accessTokenGenerator.generate(
                user.getId(),
                user.getRole()
        )).willReturn(issuedAccessToken);
        given(refreshTokenGenerator.generate())
                .willReturn(RAW_REFRESH_TOKEN);
        given(refreshTokenHasher.hash(RAW_REFRESH_TOKEN))
                .willReturn(REFRESH_TOKEN_HASH);
        given(clientIpHasher.hash(CLIENT_IP))
                .willReturn(CLIENT_IP_HASH);

        LoginResult result = loginService.login(
                request,
                CLIENT_IP,
                longUserAgent
        );

        ArgumentCaptor<RefreshToken> refreshTokenCaptor =
                ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository)
                .save(refreshTokenCaptor.capture());

        RefreshToken savedRefreshToken =
                refreshTokenCaptor.getValue();

        assertThat(result.refreshToken())
                .isEqualTo(RAW_REFRESH_TOKEN);
        assertThat(result.response().accessToken())
                .isEqualTo("access-token");
        assertThat(result.response().tokenType())
                .isEqualTo("Bearer");
        assertThat(result.response().expiresIn())
                .isEqualTo(1_800);
        assertThat(result.response().user().userId())
                .isEqualTo(user.getId());
        assertThat(savedRefreshToken.getUser())
                .isSameAs(user);
        assertThat(savedRefreshToken.getTokenHash())
                .isEqualTo(REFRESH_TOKEN_HASH)
                .isNotEqualTo(RAW_REFRESH_TOKEN);
        assertThat(savedRefreshToken.getTokenFamilyId())
                .isNotNull();
        assertThat(savedRefreshToken.getExpiresAt())
                .isEqualTo(ISSUED_AT.plus(Duration.ofDays(14)));
        assertThat(savedRefreshToken.getCreatedIpHash())
                .isEqualTo(CLIENT_IP_HASH);
        assertThat(savedRefreshToken.getUserAgent()
                .codePointCount(
                        0,
                        savedRefreshToken.getUserAgent().length()
                )).isEqualTo(500);
        assertThat(user.getLastLoginAt())
                .isEqualTo(ISSUED_AT);
    }

    @Test
    void 존재하지_않는_이메일도_더미_비밀번호를_검증하고_동일한_오류를_반환한다() {
        LoginRequest request = new LoginRequest(
                NORMALIZED_EMAIL,
                RAW_PASSWORD
        );
        given(userRepository.findByEmail(NORMALIZED_EMAIL))
                .willReturn(Optional.empty());
        given(passwordEncoder.matches(
                eq(RAW_PASSWORD),
                startsWith("{bcrypt}")
        )).willReturn(false);

        assertThatThrownBy(() -> loginService.login(
                request,
                CLIENT_IP,
                "test-agent"
        )).isInstanceOfSatisfying(
                ApiException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS)
        );

        verify(passwordEncoder).matches(
                eq(RAW_PASSWORD),
                startsWith("{bcrypt}")
        );
        verify(accessTokenGenerator, never())
                .generate(any(), any());
        verify(refreshTokenRepository, never())
                .save(any());
    }

    @Test
    void 비밀번호가_틀리면_존재하지_않는_이메일과_동일한_오류를_반환한다() {
        User user = createUser();
        LoginRequest request = new LoginRequest(
                NORMALIZED_EMAIL,
                "wrong-password"
        );
        given(userRepository.findByEmail(NORMALIZED_EMAIL))
                .willReturn(Optional.of(user));
        given(passwordEncoder.matches(
                "wrong-password",
                STORED_PASSWORD_HASH
        )).willReturn(false);

        assertThatThrownBy(() -> loginService.login(
                request,
                CLIENT_IP,
                "test-agent"
        )).isInstanceOfSatisfying(
                ApiException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS)
        );

        verify(accessTokenGenerator, never())
                .generate(any(), any());
        verify(refreshTokenRepository, never())
                .save(any());
    }

    @Test
    void 잠긴_계정은_비밀번호_검증_후_로그인을_거부한다() {
        User user = createUser();
        ReflectionTestUtils.setField(
                user,
                "status",
                UserStatus.LOCKED
        );
        given(userRepository.findByEmail(NORMALIZED_EMAIL))
                .willReturn(Optional.of(user));
        given(passwordEncoder.matches(
                RAW_PASSWORD,
                STORED_PASSWORD_HASH
        )).willReturn(true);

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest(NORMALIZED_EMAIL, RAW_PASSWORD),
                CLIENT_IP,
                "test-agent"
        )).isInstanceOfSatisfying(
                ApiException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(AuthErrorCode.ACCOUNT_LOCKED)
        );

        verify(accessTokenGenerator, never())
                .generate(any(), any());
        verify(refreshTokenRepository, never())
                .save(any());
    }

    @Test
    void 탈퇴한_계정은_비밀번호_검증_후_로그인을_거부한다() {
        User user = createUser();
        ReflectionTestUtils.setField(
                user,
                "status",
                UserStatus.WITHDRAWN
        );
        given(userRepository.findByEmail(NORMALIZED_EMAIL))
                .willReturn(Optional.of(user));
        given(passwordEncoder.matches(
                RAW_PASSWORD,
                STORED_PASSWORD_HASH
        )).willReturn(true);

        assertThatThrownBy(() -> loginService.login(
                new LoginRequest(NORMALIZED_EMAIL, RAW_PASSWORD),
                CLIENT_IP,
                "test-agent"
        )).isInstanceOfSatisfying(
                ApiException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(UserErrorCode.ACCOUNT_WITHDRAWN)
        );

        verify(accessTokenGenerator, never())
                .generate(any(), any());
        verify(refreshTokenRepository, never())
                .save(any());
    }

    private User createUser() {
        return User.create(
                NORMALIZED_EMAIL,
                STORED_PASSWORD_HASH,
                "학습자"
        );
    }
}
