package com.aiprofessor.backend.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Getter
@Table(name = "refresh_tokens")
@EntityListeners(AuditingEntityListener.class)
public class RefreshToken {

    private static final Pattern SHA_256_HEX_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @JdbcTypeCode(SqlTypes.CHAR) /*SQL DDL 타입 지정*/
    // columnDefinition을 통해 Hibernate가 사용하는 JDBC 타입 지정
    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "token_family_id", nullable = false)
    private UUID tokenFamilyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_token_id")
    private UUID replacedByTokenId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "created_ip_hash", length = 64, columnDefinition = "char(64)")
    private String createdIpHash;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {} /* JPA에는 기본 생성자를 제공하되, 애플리케이션 코드가 필수 값 없이 엔티티를 생성하는 것은 막기 위한 선언입니다. */

    private RefreshToken(
            UUID id,
            User user,
            String tokenHash,
            UUID tokenFamilyId,
            Instant expiresAt,
            String createdIpHash,
            String userAgent
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.tokenHash = validateHash(tokenHash, "tokenHash");
        this.tokenFamilyId = Objects.requireNonNull(tokenFamilyId, "tokenFamilyId must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.createdIpHash = validateNullableHash(createdIpHash, "createdIpHash");
        this.userAgent = userAgent;
    }

    private static String validateNullableHash(
            String hash,
            String fieldName
    ) {
        if (hash == null) {
            return null;
        }

        return validateHash(hash, fieldName);
    }

    private static String validateHash(
            String hash,
            String fieldName
    ) {
        Objects.requireNonNull(hash,fieldName + " must not be null"
        );

        if (!SHA_256_HEX_PATTERN.matcher(hash).matches()) {
            throw new IllegalArgumentException(fieldName+ " must be a lowercase SHA-256 hex string");
        }

        return hash;
    }

    public static RefreshToken issue(
            User user,
            String tokenHash,
            UUID tokenFamilyId,
            Instant expiresAt,
            String createdIpHash,
            String userAgent
    ) {
        return new RefreshToken(
                UUID.randomUUID(),
                user,
                tokenHash,
                tokenFamilyId,
                expiresAt,
                createdIpHash,
                validateUserAgent(userAgent)
        );
    }

    private static String validateUserAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }

        int length = userAgent.codePointCount(0, userAgent.length());

        if (length > 500) {
            throw new IllegalArgumentException("userAgent must be less than 500 characters");
        }

        return userAgent;
    }
}
