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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
class RefreshTokenRotationService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenHasher refreshTokenHasher;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final AccessTokenGenerator accessTokenGenerator;
    private final AuthTokenProperties authTokenProperties;
    private final Clock clock;

    @Transactional
    public RefreshRotationResult rotate(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ApiException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        String tokenHash = refreshTokenHasher.hash(rawRefreshToken);
        RefreshToken current = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new ApiException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        // 잠금을 기다리는 동안 토큰이 만료될 수 있으므로, 잠금 조회가 끝난 뒤의 시각을 확인
        Instant now = clock.instant();
        if (current.isRevokedOrReplace()) {
            throw new ApiException(AuthErrorCode.INVALID_REFRESH_TOKEN);
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
}
