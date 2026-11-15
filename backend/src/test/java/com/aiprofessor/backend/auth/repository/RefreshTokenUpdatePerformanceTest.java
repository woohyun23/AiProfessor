package com.aiprofessor.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiprofessor.backend.TestcontainersConfiguration;
import com.aiprofessor.backend.auth.domain.RefreshToken;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.support.config.JpaConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("performance")
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefreshTokenUpdatePerformanceTest {

    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASUREMENT_ITERATIONS = 20;
    private static final List<Integer> TOKEN_COUNTS =
            List.of(1, 10, 100, 1_000);
    private static final Path REPORT_DIRECTORY =
            Path.of("build", "reports", "benchmarks");
    private static final AtomicInteger HASH_SEQUENCE = new AtomicInteger();

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void JPA_변경_감지와_JPQL_벌크_업데이트를_비교한다()
            throws IOException {
        List<BenchmarkSummary> summaries = new ArrayList<>();

        for (int tokenCount : TOKEN_COUNTS) {
            BenchmarkFixture fixture = createFixture(tokenCount);

            warmUp(fixture);
            summaries.addAll(measure(fixture));
        }

        writeReports(summaries);

        assertThat(summaries).hasSize(TOKEN_COUNTS.size() * 2);
    }

    private void warmUp(BenchmarkFixture fixture) {
        for (int iteration = 0; iteration < WARMUP_ITERATIONS; iteration++) {
            if (iteration % 2 == 0) {
                executeJpaUpdate(fixture);
                executeBulkUpdate(fixture);
            } else {
                executeBulkUpdate(fixture);
                executeJpaUpdate(fixture);
            }
        }
    }

    private List<BenchmarkSummary> measure(BenchmarkFixture fixture) {
        List<Measurement> jpaMeasurements = new ArrayList<>();
        List<Measurement> bulkMeasurements = new ArrayList<>();

        for (int iteration = 0;
             iteration < MEASUREMENT_ITERATIONS;
             iteration++) {
            if (iteration % 2 == 0) {
                jpaMeasurements.add(executeJpaUpdate(fixture));
                bulkMeasurements.add(executeBulkUpdate(fixture));
            } else {
                bulkMeasurements.add(executeBulkUpdate(fixture));
                jpaMeasurements.add(executeJpaUpdate(fixture));
            }
        }

        return List.of(
                BenchmarkSummary.from(
                        fixture.tokenCount(),
                        "JPA_DIRTY_CHECKING",
                        jpaMeasurements
                ),
                BenchmarkSummary.from(
                        fixture.tokenCount(),
                        "JPQL_BULK_UPDATE",
                        bulkMeasurements
                )
        );
    }

    private Measurement executeJpaUpdate(BenchmarkFixture fixture) {
        resetRevokedAt(fixture.tokenFamilyId());

        return measureOperation(() -> {
            List<RefreshToken> tokens = entityManager.createQuery(
                            """
                            SELECT refreshToken
                            FROM RefreshToken refreshToken
                            WHERE refreshToken.tokenFamilyId = :tokenFamilyId
                              AND refreshToken.revokedAt IS NULL
                              AND refreshToken.expiresAt > :revokedAt
                            """,
                            RefreshToken.class
                    )
                    .setParameter(
                            "tokenFamilyId",
                            fixture.tokenFamilyId()
                    )
                    .setParameter("revokedAt", fixture.revokedAt())
                    .getResultList();

            tokens.forEach(token -> token.revoke(fixture.revokedAt()));

            return tokens.size();
        }, fixture);
    }

    private Measurement executeBulkUpdate(BenchmarkFixture fixture) {
        resetRevokedAt(fixture.tokenFamilyId());

        return measureOperation(() ->
                refreshTokenRepository
                        .revokeActiveByTokenFamily(
                                fixture.tokenFamilyId(),
                                fixture.revokedAt()
                        ),
                fixture
        );
    }

    private Measurement measureOperation(
            IntSupplier operation,
            BenchmarkFixture fixture
    ) {
        Statistics statistics = entityManagerFactory
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.clear();

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);
        long startedAt = System.nanoTime();

        Integer updatedCount = transactionTemplate.execute(status ->
                operation.getAsInt()
        );

        long elapsedNanos = System.nanoTime() - startedAt;
        long preparedStatementCount = statistics
                .getPrepareStatementCount();

        assertThat(updatedCount).isEqualTo(fixture.tokenCount());
        assertThat(countRevokedTokens(fixture.tokenFamilyId()))
                .isEqualTo(fixture.tokenCount());

        return new Measurement(
                elapsedNanos,
                preparedStatementCount
        );
    }

    private BenchmarkFixture createFixture(int tokenCount) {
        User user = userRepository.saveAndFlush(User.create(
                "benchmark-%d@example.com".formatted(tokenCount),
                "{bcrypt}encoded-password",
                "성능측정"
        ));
        UUID tokenFamilyId = UUID.randomUUID();
        Instant revokedAt = Instant.now();
        Instant expiresAt = revokedAt.plus(Duration.ofDays(14));
        List<RefreshToken> tokens = new ArrayList<>(tokenCount);

        for (int index = 0; index < tokenCount; index++) {
            tokens.add(RefreshToken.issue(
                    user,
                    nextTokenHash(),
                    tokenFamilyId,
                    expiresAt,
                    null,
                    "performance-test"
            ));
        }

        refreshTokenRepository.saveAllAndFlush(tokens);

        return new BenchmarkFixture(
                tokenFamilyId,
                tokenCount,
                revokedAt
        );
    }

    private void resetRevokedAt(UUID tokenFamilyId) {
        jdbcTemplate.update(
                """
                UPDATE refresh_tokens
                SET revoked_at = NULL
                WHERE token_family_id = ?
                """,
                tokenFamilyId
        );
    }

    private int countRevokedTokens(UUID tokenFamilyId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM refresh_tokens
                WHERE token_family_id = ?
                  AND revoked_at IS NOT NULL
                """,
                Integer.class,
                tokenFamilyId
        );

        return count == null ? 0 : count;
    }

    private String nextTokenHash() {
        return "%064x".formatted(HASH_SEQUENCE.incrementAndGet());
    }

    private void writeReports(List<BenchmarkSummary> summaries)
            throws IOException {
        Files.createDirectories(REPORT_DIRECTORY);
        Files.writeString(
                REPORT_DIRECTORY.resolve("refresh-token-update.csv"),
                createCsv(summaries),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                REPORT_DIRECTORY.resolve("refresh-token-update.md"),
                createMarkdown(summaries),
                StandardCharsets.UTF_8
        );
    }

    private String createCsv(List<BenchmarkSummary> summaries) {
        StringBuilder csv = new StringBuilder(
                "token_count,method,iterations,p50_ms,p95_ms,"
                        + "prepared_statement_p50\n"
        );

        summaries.stream()
                .sorted(BenchmarkSummary.ORDER)
                .forEach(summary -> csv.append(String.format(
                        Locale.ROOT,
                        "%d,%s,%d,%.3f,%.3f,%d%n",
                        summary.tokenCount(),
                        summary.method(),
                        MEASUREMENT_ITERATIONS,
                        summary.p50Millis(),
                        summary.p95Millis(),
                        summary.preparedStatementP50()
                )));

        return csv.toString();
    }

    private String createMarkdown(List<BenchmarkSummary> summaries) {
        StringBuilder markdown = new StringBuilder()
                .append("# Refresh Token update benchmark\n\n")
                .append("- Generated at: ")
                .append(Instant.now())
                .append("\n- Java: ")
                .append(System.getProperty("java.version"))
                .append("\n- OS: ")
                .append(System.getProperty("os.name"))
                .append(" ")
                .append(System.getProperty("os.arch"))
                .append("\n- Database: ")
                .append(databaseVersion())
                .append("\n- Hibernate JDBC batch size: ")
                .append(jdbcBatchSize())
                .append("\n- Warm-up iterations: ")
                .append(WARMUP_ITERATIONS)
                .append("\n- Measurement iterations: ")
                .append(MEASUREMENT_ITERATIONS)
                .append("\n- Timing scope: transaction start through commit\n")
                .append("- SQL metric: Hibernate prepared statement count\n")
                .append("- Scope: local Testcontainers benchmark; ")
                .append("not a production performance guarantee\n\n")
                .append("| Tokens | Method | p50 (ms) | p95 (ms) | ")
                .append("PreparedStatement p50 |\n")
                .append("|---:|---|---:|---:|---:|\n");

        summaries.stream()
                .sorted(BenchmarkSummary.ORDER)
                .forEach(summary -> markdown.append(String.format(
                        Locale.ROOT,
                        "| %d | %s | %.3f | %.3f | %d |%n",
                        summary.tokenCount(),
                        summary.method(),
                        summary.p50Millis(),
                        summary.p95Millis(),
                        summary.preparedStatementP50()
                )));

        return markdown.toString();
    }

    private String databaseVersion() {
        String version = jdbcTemplate.queryForObject(
                "SELECT version()",
                String.class
        );

        return version == null ? "unknown" : version;
    }

    private Object jdbcBatchSize() {
        return entityManagerFactory
                .getProperties()
                .getOrDefault(
                        "hibernate.jdbc.batch_size",
                        "0 (default; disabled)"
                );
    }

    private record BenchmarkFixture(
            UUID tokenFamilyId,
            int tokenCount,
            Instant revokedAt
    ) {
    }

    private record Measurement(
            long elapsedNanos,
            long preparedStatementCount
    ) {
    }

    private record BenchmarkSummary(
            int tokenCount,
            String method,
            double p50Millis,
            double p95Millis,
            long preparedStatementP50
    ) {

        private static final Comparator<BenchmarkSummary> ORDER =
                Comparator.comparingInt(BenchmarkSummary::tokenCount)
                        .thenComparing(BenchmarkSummary::method);

        private static BenchmarkSummary from(
                int tokenCount,
                String method,
                List<Measurement> measurements
        ) {
            List<Long> durations = measurements.stream()
                    .map(Measurement::elapsedNanos)
                    .sorted()
                    .toList();
            List<Long> statementCounts = measurements.stream()
                    .map(Measurement::preparedStatementCount)
                    .sorted()
                    .toList();

            return new BenchmarkSummary(
                    tokenCount,
                    method,
                    nanosToMillis(percentile(durations, 0.50)),
                    nanosToMillis(percentile(durations, 0.95)),
                    percentile(statementCounts, 0.50)
            );
        }

        private static long percentile(
                List<Long> sortedValues,
                double percentile
        ) {
            int index = (int) Math.ceil(
                    percentile * sortedValues.size()
            ) - 1;

            return sortedValues.get(Math.max(index, 0));
        }

        private static double nanosToMillis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }
}
