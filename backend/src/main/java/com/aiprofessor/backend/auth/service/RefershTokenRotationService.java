package com.aiprofessor.backend.auth.service;

import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import com.aiprofessor.backend.auth.domain.RefreshToken;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.auth.error.UserErrorCode;
import com.aiprofessor.backend.auth.repository.RefreshTokenRepository;
import com.aiprofessor.backend.auth.token.AccessTokenGenerator;
import com.aiprofessor.backend.auth.token.IssuedAccessToken;
import com.aiprofessor.backend.auth.token.RefreshTokenGenerator;
import com.aiprofessor.backend.auth.token.RefreshTokenHasher;
import com.aiprofessor.backend.support.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class RefreshTokenRotationService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenHasher refreshTokenHasher;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final AccessTokenGenerator accessTokenGenerator;
    private final AuthTokenProperties authTokenProperties;
    private final Clock clock;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            // noRollbackFor - 재사용 전용 예외 한 종류에만 적용
            // -> 폐기 쿼리가 성공한 뒤 이 예외를 던지면 Spring이 폐기를 커밋하고 호출자에게 예외 전달, JWT 서명 실패나 DB 오류는 기존대로 롤백
            noRollbackFor = RefreshTokenReuseException.class
    )
    public RefreshRotationResult rotate(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ApiException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        String tokenHash = refreshTokenHasher.hash(rawRefreshToken);
        UUID tokenFamilyId = refreshTokenRepository.findTokenFamilyIdByTokenHash(tokenHash)
                .orElseThrow(() -> new ApiException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        lockTokenFamily(tokenFamilyId);

        RefreshToken current = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new ApiException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        // 잠금을 기다리는 동안 토큰이 만료될 수 있으므로, 잠금 조회가 끝난 뒤의 시각을 확인
        Instant now = clock.instant();
        if (current.isRevokedOrReplace()) {
            refreshTokenRepository.revokeActiveByTokenFamily(tokenFamilyId, now);
            throw new RefreshTokenReuseException();
        }

        if (current.isExpired(now)) {
            throw new ApiException(AuthErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        User user = current.getUser();
        switch (user.getStatus()) {
            case ACTIVE -> { }
            case LOCKED -> throw new ApiException(AuthErrorCode.ACCOUNT_LOCKED);
            case WITHDRAWN -> throw new ApiException(UserErrorCode.ACCOUNT_WITHDRAWN);
        }

        String nextRawToken = refreshTokenGenerator.generate();
        RefreshToken replacement = RefreshToken.issue(
                user,
                refreshTokenHasher.hash(nextRawToken),
                current.getTokenFamilyId(),
                now.plus(authTokenProperties.refreshTokenTtl()),
                null,
                null
        );

        // 기존 행이 외래 키로 참조할 새 토큰을 먼저 INSERT해 기존 행의 replaced_by_token_id가 참조 가능
        RefreshToken savedReplacement = refreshTokenRepository.save(replacement);
        current.replaceWith(savedReplacement, now);

        IssuedAccessToken accessToken = accessTokenGenerator.generate(
                user.getId(),
                user.getRole()
        );

        return new RefreshRotationResult(
                accessToken,
                nextRawToken
        );
    }

    private void lockTokenFamily(UUID tokenFamilyId) {
        long lockKey = tokenFamilyId.getMostSignificantBits() ^ tokenFamilyId.getLeastSignificantBits();
        // 같은 Token Family에서 정ㅇ상 회전과 재사용 탐지가 동시에 진행되지 않도록 하기 위해 pg_advisory_xact_lock(?) 호출
        // 기존의 findByTokenHashForUpdate()는 요청에 사용된 토큰 한 행을 잠그지만, 사용된 토큰이 서로 달라도 같은 Family라면 한 요청이 끝날 때까지 다음 요청을 대기하도록 잠금
        jdbcTemplate.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(?)", Integer.class ,lockKey);
    }
}
