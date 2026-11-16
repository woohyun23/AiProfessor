package com.aiprofessor.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "aiprofessor.auth.token.refresh-token-ttl=14d"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableConfigurationProperties(AuthTokenProperties.class)
@Import({
        TestcontainersConfiguration.class,
        JpaConfig.class,
        RefreshTokenRotationService.class,
        AccessTokenGenerator.class,
        RefreshTokenGenerator.class,
        RefreshTokenHasher.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefreshTokenRotationServiceTest {

    @Autowired
    private RefreshTokenRotationService rotationService;

    @Autowired
    private RefreshTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenGenerator tokenGenerator;

    @Autowired
    private RefreshTokenHasher tokenHasher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private Clock clock;

    private final UUID familyId = UUID.randomUUID();
    private User user;
    private Instant now;
    private String rawToken;

    @BeforeEach
    void setUp() {
        // 기존 토큰의 만료 시각은 JPA가 기록하는 실제 생성 시각보다 뒤여야 한다.
        now = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        given(clock.instant()).willReturn(now);
        user = userRepository.saveAndFlush(User.create(
                familyId + "@example.com", "{bcrypt}encoded-password", "학습자"
        ));
        rawToken = tokenGenerator.generate();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM refresh_tokens WHERE token_family_id = ?", familyId);
        if (user != null) {
            userRepository.deleteById(user.getId());
        }
    }

    @Test
    void 같은_Family에_새_토큰을_저장하고_기존_토큰을_폐기한다() {
        RefreshToken original = issueOriginal(now.plusSeconds(60));
        given(jwtEncoder.encode(any())).willReturn(Jwt.withTokenValue("test-access-token")
                .header("alg", "RS256")
                .subject(user.getId().toString())
                .build());

        RefreshRotationResult result = rotationService.rotate(rawToken);

        RefreshToken retired = tokenRepository.findById(original.getId()).orElseThrow();
        assertThat(retired.getRevokedAt()).isEqualTo(now);
        assertThat(retired.getReplacedByTokenId()).isNotNull().isNotEqualTo(original.getId());
        RefreshToken replacement = tokenRepository
                .findById(retired.getReplacedByTokenId()).orElseThrow();

        assertThat(result.accessToken().tokenValue()).isEqualTo("test-access-token");
        assertThat(result.refreshToken()).isNotEqualTo(rawToken);
        assertThat(replacement.getTokenFamilyId()).isEqualTo(familyId);
        assertThat(replacement.getTokenHash()).isEqualTo(tokenHasher.hash(result.refreshToken()));
        assertThat(replacement.getExpiresAt()).isEqualTo(now.plus(14, ChronoUnit.DAYS));
        assertThat(replacement.getRevokedAt()).isNull();
        assertThat(countFamilyTokens()).isEqualTo(2L);

        assertThatThrownBy(() -> rotationService.rotate(rawToken))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
    }

    @Test
    void 만료_시각에_도달한_토큰은_갱신할_수_없다() {
        issueOriginal(now);

        assertThatThrownBy(() -> rotationService.rotate(rawToken))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_EXPIRED));
        assertThat(countFamilyTokens()).isEqualTo(1L);
    }

    @Test
    void Access_Token_발급에_실패하면_회전_변경을_롤백한다() {
        RefreshToken original = issueOriginal(now.plusSeconds(60));
        JwtEncodingException signingFailure = new JwtEncodingException("test signing failure");
        given(jwtEncoder.encode(any())).willThrow(signingFailure);

        assertThatThrownBy(() -> rotationService.rotate(rawToken)).isSameAs(signingFailure);

        RefreshToken unchanged = tokenRepository.findById(original.getId()).orElseThrow();
        assertThat(unchanged.getRevokedAt()).isNull();
        assertThat(unchanged.getReplacedByTokenId()).isNull();
        assertThat(countFamilyTokens()).isEqualTo(1L);
    }

    private RefreshToken issueOriginal(Instant expiresAt) {
        return tokenRepository.saveAndFlush(RefreshToken.issue(
                user, tokenHasher.hash(rawToken), familyId, expiresAt, null, null
        ));
    }

    private Long countFamilyTokens() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_family_id = ?",
                Long.class, familyId
        );
    }
}
