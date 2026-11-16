package com.aiprofessor.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aiprofessor.backend.TestcontainersConfiguration;
import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import com.aiprofessor.backend.auth.domain.RefreshToken;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.auth.repository.RefreshTokenRepository;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.auth.token.AccessTokenGenerator;
import com.aiprofessor.backend.auth.token.RefreshTokenGenerator;
import com.aiprofessor.backend.auth.token.RefreshTokenHasher;
import com.aiprofessor.backend.support.config.JpaConfig;
import com.aiprofessor.backend.support.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableConfigurationProperties(AuthTokenProperties.class)
@Import({
        TestcontainersConfiguration.class,
        JpaConfig.class,
        LogoutService.class,
        RefreshTokenRotationService.class,
        AccessTokenGenerator.class,
        RefreshTokenGenerator.class,
        RefreshTokenHasher.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LogoutServiceTest {

    @Autowired
    private LogoutService logoutService;

    @Autowired
    private RefreshTokenRotationService rotationService;

    @Autowired
    private RefreshTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenHasher tokenHasher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private JwtEncoder jwtEncoder;

    private final List<UUID> userIds = new ArrayList<>();
    private final Instant now = Instant.now().plus(1, ChronoUnit.DAYS)
            .truncatedTo(ChronoUnit.MICROS);

    @BeforeEach
    void setUp() {
        given(clock.instant()).willReturn(now);
    }

    @AfterEach
    void cleanUp() {
        for (UUID userId : userIds) {
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
            userRepository.deleteById(userId);
        }
    }

    @Test
    void 현재_토큰만_폐기하고_해당_토큰으로_다시_갱신할_수_없다() {
        User owner = createUser();
        String rawToken = "rft_" + UUID.randomUUID();
        RefreshToken current = saveToken(owner, rawToken);
        RefreshToken otherSession = saveToken(owner, "rft_" + UUID.randomUUID());

        logoutService.logout(owner.getId(), rawToken);

        assertThat(loadToken(current).getRevokedAt()).isEqualTo(now);
        assertThat(loadToken(otherSession).getRevokedAt()).isNull();

        assertThatThrownBy(() -> rotationService.rotate(rawToken))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSED));
        assertThat(loadToken(otherSession).getRevokedAt()).isNull();
        verifyNoInteractions(jwtEncoder);
    }

    @Test
    void 다른_사용자의_토큰은_폐기하지_않는다() {
        User authenticatedUser = createUser();
        User owner = createUser();
        String rawToken = "rft_" + UUID.randomUUID();
        RefreshToken otherUsersToken = saveToken(owner, rawToken);

        logoutService.logout(authenticatedUser.getId(), rawToken);

        assertThat(loadToken(otherUsersToken).getRevokedAt()).isNull();
    }

    @Test
    void 반복_로그아웃은_최초_폐기_시각을_유지한다() {
        User owner = createUser();
        String rawToken = "rft_" + UUID.randomUUID();
        RefreshToken current = saveToken(owner, rawToken);

        logoutService.logout(owner.getId(), rawToken);
        given(clock.instant()).willReturn(now.plusSeconds(1));
        logoutService.logout(owner.getId(), rawToken);

        assertThat(loadToken(current).getRevokedAt()).isEqualTo(now);
    }

    private User createUser() {
        User user = userRepository.saveAndFlush(User.create(
                UUID.randomUUID() + "@example.com", "{bcrypt}password-hash", "학습자"
        ));
        userIds.add(user.getId());
        return user;
    }

    private RefreshToken saveToken(User user, String rawToken) {
        return tokenRepository.saveAndFlush(RefreshToken.issue(
                user, tokenHasher.hash(rawToken), UUID.randomUUID(),
                now.plusSeconds(60), null, null
        ));
    }

    private RefreshToken loadToken(RefreshToken token) {
        return tokenRepository.findById(token.getId()).orElseThrow();
    }
}
