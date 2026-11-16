package com.aiprofessor.backend.auth.service;

import com.aiprofessor.backend.auth.domain.RefreshToken;
import com.aiprofessor.backend.auth.repository.RefreshTokenRepository;
import com.aiprofessor.backend.auth.token.RefreshTokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LogoutService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenHasher refreshTokenHasher;
    private final Clock clock;

    @Transactional
    public void logout(UUID authenticatedUserId, String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }

        String tokenHash = refreshTokenHasher.hash(rawRefreshToken);
        RefreshToken token = refreshTokenRepository
                .findByTokenHashAndUserIdForUpdate(tokenHash, authenticatedUserId)
                .orElse(null);

        if (token == null) {
            return;
        }

        Instant now = clock.instant();
        if (token.isRevokedOrReplace() || token.isExpired(now)) {
            return;
        }

        token.revoke(now);
    }
}
