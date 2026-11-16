package com.aiprofessor.backend.auth.repository;

import com.aiprofessor.backend.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // 조회한 Refresh Token 행에 비관적 쓰기 잠금 획득
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT refreshToken
            FROM RefreshToken refreshToken
            WHERE refreshToken.tokenHash = :tokenHash
    """)
    Optional<RefreshToken> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash
    );

    // 같은 Token Family에 속한 활성 토큰 일괄 폐기 -> 반환 값 : 폐기된 토큰 개수
    @Modifying(
            // 벌크 업데이트 전에 영속성 컨텍스트의 변경 사항을 DB에 반영
            flushAutomatically = true,
            // 벌크 업데이트 후 DB와 불일치할 수 있는 영속성 컨텍스트 초기화
            clearAutomatically = true
    )
    @Query("""
            UPDATE RefreshToken refreshToken
            SET refreshToken.revokedAt = :revokedAt
            WHERE refreshToken.tokenFamilyId = :tokenFamilyId
            AND refreshToken.revokedAt IS NULL
            AND refreshToken.expiresAt > :revokedAt
        """)
    int revokeActiveByTokenFamily(
            @Param("tokenFamilyId") UUID tokenFamilyId,
            @Param("revokedAt") Instant revokedAt
    );

    @Query("""
        SELECT refreshToken.tokenFamilyId
        FROM RefreshToken refreshToken
        WHERE refreshToken.tokenHash = :tokenHash
        """)
    Optional<UUID> findTokenFamilyIdByTokenHash(@Param("tokenHash") String tokenHash);
}
