package com.aiprofessor.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiprofessor.backend.TestcontainersConfiguration;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.dto.request.ChangePasswordRequest;
import com.aiprofessor.backend.auth.dto.request.LoginRequest;
import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.auth.error.UserErrorCode;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.support.error.ApiException;
import com.aiprofessor.backend.support.error.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class PasswordChangeServiceTest {

    private static final String OLD_PASSWORD = "old-password";
    private static final String NEW_PASSWORD = "new-password";
    private static final ChangePasswordRequest VALID_CHANGE =
            new ChangePasswordRequest(OLD_PASSWORD, NEW_PASSWORD);
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private PasswordChangeService passwordChangeService;

    @Autowired
    private LoginService loginService;

    @Autowired
    private RefreshTokenRotationService rotationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID userId : userIds) {
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
            userRepository.deleteById(userId);
        }
    }

    @Test
    void 새_비밀번호로만_로그인할_수_있고_본인의_모든_기존_세션이_폐기된다() {
        User user = createUser();
        User otherUser = createUser();
        String firstSession = login(user, OLD_PASSWORD);
        String secondSession = login(user, OLD_PASSWORD);
        String otherSession = login(otherUser, OLD_PASSWORD);
        assertThat(activeTokenCount(user)).isEqualTo(2);

        passwordChangeService.changePassword(user.getId(), VALID_CHANGE);

        assertThat(activeTokenCount(user)).isZero();
        assertThatThrownBy(() -> login(user, OLD_PASSWORD))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertErrorCode(failure, AuthErrorCode.INVALID_CREDENTIALS));
        assertRefreshRejected(firstSession);
        assertRefreshRejected(secondSession);
        assertThat(login(user, NEW_PASSWORD)).isNotBlank();
        assertThat(rotationService.rotate(otherSession).refreshToken()).isNotBlank();
    }

    @ParameterizedTest
    @CsvSource({
            "wrong-password, new-password, CURRENT_PASSWORD_MISMATCH",
            "old-password, old-password, SAME_PASSWORD"
    })
    void 비밀번호_변경이_거부되면_기존_해시와_세션을_유지한다(
            String currentPassword,
            String newPassword,
            UserErrorCode expectedCode
    ) {
        User user = createUser();
        String oldRefreshToken = login(user, OLD_PASSWORD);

        assertThatThrownBy(() -> passwordChangeService.changePassword(
                user.getId(), new ChangePasswordRequest(currentPassword, newPassword)))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertErrorCode(failure, expectedCode));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getPasswordHash())
                .isEqualTo(user.getPasswordHash());
        assertThat(activeTokenCount(user)).isEqualTo(1);
        assertThat(rotationService.rotate(oldRefreshToken).refreshToken()).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(CredentialRequest.class)
    void 로그인이나_갱신이_먼저_잠그면_비밀번호_변경이_새로_발급된_토큰까지_폐기한다(
            CredentialRequest request
    ) throws Exception {
        User user = createUser();
        String existingSession = login(user, OLD_PASSWORD);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Race<String, Void> race = transaction().execute(status -> {
                String issuedToken = useOldCredential(request, user, existingSession);
                Future<Void> laterChange = startBlocked(executor, () -> {
                    passwordChangeService.changePassword(user.getId(), VALID_CHANGE);
                    return null;
                });
                return new Race<>(issuedToken, laterChange);
            });

            race.later().get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(activeTokenCount(user)).isZero();
            assertRefreshRejected(race.earlier());
        }
    }

    @ParameterizedTest
    @EnumSource(CredentialRequest.class)
    void 비밀번호_변경이_먼저_잠그면_대기하던_기존_로그인이나_갱신이_거부된다(
            CredentialRequest request
    ) {
        User user = createUser();
        String existingSession = login(user, OLD_PASSWORD);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<String> laterRequest = transaction().execute(status -> {
                passwordChangeService.changePassword(user.getId(), VALID_CHANGE);
                return startBlocked(executor, () -> useOldCredential(request, user, existingSession));
            });

            ErrorCode expectedCode = switch (request) {
                case LOGIN -> AuthErrorCode.INVALID_CREDENTIALS;
                case REFRESH -> AuthErrorCode.REFRESH_TOKEN_REUSED;
            };
            assertThatThrownBy(() -> laterRequest.get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, failure ->
                            assertThat(failure.getCause()).isInstanceOfSatisfying(
                                    ApiException.class, ex -> assertErrorCode(ex, expectedCode)));
            assertThat(activeTokenCount(user)).isZero();
        }
    }

    private <T> Future<T> startBlocked(ExecutorService executor, Supplier<T> action) {
        int holderPid = backendPid();
        CompletableFuture<Integer> waitingPid = new CompletableFuture<>();
        Future<T> result = executor.submit(() -> transaction().execute(status -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
            waitingPid.complete(backendPid());
            return action.get();
        }));

        // PostgreSQL에서 실제 잠금 대기가 관찰된 뒤에만 먼저 시작한 트랜잭션을 커밋한다.
        try {
            int waiterPid = waitingPid.get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline && !result.isDone()) {
                Boolean blocked = jdbcTemplate.queryForObject(
                        "SELECT ? = ANY(pg_blocking_pids(?))",
                        Boolean.class, holderPid, waiterPid
                );
                if (Boolean.TRUE.equals(blocked)) {
                    return result;
                }
                Thread.sleep(20);
            }
            throw new AssertionError("두 번째 요청이 첫 번째 트랜잭션의 잠금을 기다리지 않았습니다.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("잠금 대기 확인이 중단됐습니다.", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AssertionError("두 번째 트랜잭션의 DB 연결을 확인할 수 없습니다.", exception);
        }
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private int backendPid() {
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private String useOldCredential(CredentialRequest request, User user, String existingSession) {
        return switch (request) {
            case LOGIN -> login(user, OLD_PASSWORD);
            case REFRESH -> rotationService.rotate(existingSession).refreshToken();
        };
    }

    private User createUser() {
        User user = userRepository.saveAndFlush(User.create(
                UUID.randomUUID() + "@example.com", passwordEncoder.encode(OLD_PASSWORD), "학습자"
        ));
        userIds.add(user.getId());
        return user;
    }

    private String login(User user, String password) {
        return loginService.login(new LoginRequest(user.getEmail(), password), null, null).refreshToken();
    }

    private long activeTokenCount(User user) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM refresh_tokens
                WHERE user_id = ? AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                """, Long.class, user.getId());
    }

    private void assertRefreshRejected(String rawToken) {
        assertThatThrownBy(() -> rotationService.rotate(rawToken))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertErrorCode(failure, AuthErrorCode.REFRESH_TOKEN_REUSED));
    }

    private void assertErrorCode(ApiException error, ErrorCode expectedCode) {
        assertThat(error.getErrorCode()).isEqualTo(expectedCode);
    }

    private enum CredentialRequest {
        LOGIN,
        REFRESH
    }

    private record Race<T, U>(T earlier, Future<U> later) {}
}
