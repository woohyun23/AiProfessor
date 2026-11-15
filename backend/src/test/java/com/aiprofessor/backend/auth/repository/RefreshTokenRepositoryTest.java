package com.aiprofessor.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiprofessor.backend.TestcontainersConfiguration;
import com.aiprofessor.backend.auth.domain.RefreshToken;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.support.config.JpaConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaConfig.class})
class RefreshTokenRepositoryTest {

    private static final Instant REFERENCE_TIME = Instant.now();

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void Token_Hash로_Refresh_Token을_조회한다() {
        User user = saveUser("lock-query@example.com");
        String tokenHash = tokenHash(1);
        RefreshToken refreshToken = issueToken(
                user,
                tokenHash,
                UUID.randomUUID(),
                REFERENCE_TIME.plus(Duration.ofDays(14))
        );
        refreshTokenRepository.saveAndFlush(refreshToken);

        RefreshToken foundToken = refreshTokenRepository
                .findByTokenHashForUpdate(tokenHash)
                .orElseThrow();

        assertThat(foundToken.getId()).isEqualTo(refreshToken.getId());
        assertThat(foundToken.getTokenHash()).isEqualTo(tokenHash);
    }

    @Test
    void Token_Family의_활성_토큰만_일괄_폐기한다() {
        User user = saveUser("family-revoke@example.com");
        UUID targetFamilyId = UUID.randomUUID();
        UUID otherFamilyId = UUID.randomUUID();
        Instant revokedAt = REFERENCE_TIME.plus(Duration.ofDays(2));

        RefreshToken activeToken = issueToken(
                user,
                tokenHash(2),
                targetFamilyId,
                REFERENCE_TIME.plus(Duration.ofDays(14))
        );
        RefreshToken alreadyRevokedToken = issueToken(
                user,
                tokenHash(3),
                targetFamilyId,
                REFERENCE_TIME.plus(Duration.ofDays(14))
        );
        Instant previousRevokedAt =
                REFERENCE_TIME.minus(Duration.ofHours(1));
        ReflectionTestUtils.setField(
                alreadyRevokedToken,
                "revokedAt",
                previousRevokedAt
        );
        RefreshToken expiredToken = issueToken(
                user,
                tokenHash(4),
                targetFamilyId,
                REFERENCE_TIME.plus(Duration.ofDays(1))
        );
        RefreshToken otherFamilyToken = issueToken(
                user,
                tokenHash(5),
                otherFamilyId,
                REFERENCE_TIME.plus(Duration.ofDays(14))
        );
        refreshTokenRepository.saveAllAndFlush(
                List.of(
                        activeToken,
                        alreadyRevokedToken,
                        expiredToken,
                        otherFamilyToken
                )
        );

        int revokedCount = refreshTokenRepository
                .revokeActiveByTokenFamily(targetFamilyId, revokedAt);

        assertThat(revokedCount).isOne();
        assertThat(findToken(activeToken.getId()).getRevokedAt())
                .isEqualTo(revokedAt);
        assertThat(findToken(alreadyRevokedToken.getId()).getRevokedAt())
                .isEqualTo(previousRevokedAt);
        assertThat(findToken(expiredToken.getId()).getRevokedAt()).isNull();
        assertThat(findToken(otherFamilyToken.getId()).getRevokedAt())
                .isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 동일한_Refresh_Token의_잠금은_트랜잭션이_끝날_때까지_유지된다()
            throws Exception {
        User user = saveUser("concurrent-lock@example.com");
        String tokenHash = tokenHash(6);
        refreshTokenRepository.saveAndFlush(issueToken(
                user,
                tokenHash,
                UUID.randomUUID(),
                Instant.now().plus(Duration.ofDays(14))
        ));

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstLock = new CountDownLatch(1);
        CountDownLatch secondQueryStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstTransaction = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        refreshTokenRepository
                                .findByTokenHashForUpdate(tokenHash)
                                .orElseThrow();
                        firstLockAcquired.countDown();
                        await(releaseFirstLock);
                    })
            );
            assertThat(firstLockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> secondTransaction = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        secondQueryStarted.countDown();
                        refreshTokenRepository
                                .findByTokenHashForUpdate(tokenHash)
                                .orElseThrow();
                    })
            );
            assertThat(secondQueryStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() ->
                    secondTransaction.get(300, TimeUnit.MILLISECONDS)
            ).isInstanceOf(TimeoutException.class);

            releaseFirstLock.countDown();

            firstTransaction.get(5, TimeUnit.SECONDS);
            secondTransaction.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstLock.countDown();
            executor.shutdownNow();
        }
    }

    private User saveUser(String email) {
        return userRepository.saveAndFlush(User.create(
                email,
                "{bcrypt}encoded-password",
                "학습자"
        ));
    }

    private RefreshToken issueToken(
            User user,
            String tokenHash,
            UUID tokenFamilyId,
            Instant expiresAt
    ) {
        return RefreshToken.issue(
                user,
                tokenHash,
                tokenFamilyId,
                expiresAt,
                null,
                "repository-test"
        );
    }

    private RefreshToken findToken(UUID tokenId) {
        return refreshTokenRepository.findById(tokenId).orElseThrow();
    }

    private String tokenHash(int value) {
        return "%064x".formatted(value);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while waiting for latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for latch",
                    exception
            );
        }
    }
}
