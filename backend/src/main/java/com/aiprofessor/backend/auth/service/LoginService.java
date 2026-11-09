package com.aiprofessor.backend.auth.service;

import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import com.aiprofessor.backend.auth.domain.EmailNormalizer;
import com.aiprofessor.backend.auth.domain.RefreshToken;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.domain.UserStatus;
import com.aiprofessor.backend.auth.dto.request.LoginRequest;
import com.aiprofessor.backend.auth.dto.response.LoginResponse;
import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.auth.error.UserErrorCode;
import com.aiprofessor.backend.auth.repository.RefreshTokenRepository;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.auth.security.ClientIpHasher;
import com.aiprofessor.backend.auth.token.AccessTokenGenerator;
import com.aiprofessor.backend.auth.token.IssuedAccessToken;
import com.aiprofessor.backend.auth.token.RefreshTokenGenerator;
import com.aiprofessor.backend.auth.token.RefreshTokenHasher;
import com.aiprofessor.backend.support.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoginService {

    private static final int MAX_USER_AGENT_CODE_POINTS = 500;

    // 비밀번호 해시는 비밀정보가 아니며,
    // 존재하지 않는 이메일에도 실제 사용자와 유사한 BCrypt 비용을 적용하기 위한 고정 더미 해시
    private static final String DUMMY_PASSWORD_HASH =
            "{bcrypt}$2a$12$y9KjLendfojbJPnMKy4AC.Eg.2ycqyO.vYHQrBhkA3qoa9WCHG3Z2";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailNormalizer emailNormalizer;
    private final AccessTokenGenerator accessTokenGenerator;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final RefreshTokenHasher refreshTokenHasher;
    private final ClientIpHasher clientIpHasher;
    private final AuthTokenProperties authTokenProperties;

    @Transactional
    public LoginResult login(
            LoginRequest request,
            String clientIp,
            String userAgent
    ) {
        String normalizedEmail =
                emailNormalizer.normalize(request.email());

        Optional<User> optionalUser = userRepository.findByEmail(normalizedEmail);
        String passwordHash = optionalUser
                .map(User::getPasswordHash)
                .orElse(DUMMY_PASSWORD_HASH);

        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);

        if (optionalUser.isEmpty() || !passwordMatches) {
            throw new ApiException(AuthErrorCode.INVALID_CREDENTIALS);
        }

        User user = optionalUser.get();
        validateAccountStatus(user.getStatus());
        IssuedAccessToken accessToken = accessTokenGenerator.generate(
                user.getId(),
                user.getRole()
        );

        String rawRefreshToken = refreshTokenGenerator.generate();
        String refreshTokenHash = refreshTokenHasher.hash(rawRefreshToken);
        UUID tokenFamilyId = UUID.randomUUID();
        Instant refreshTokenExpiresAt = accessToken.issuedAt().plus(authTokenProperties.refreshTokenTtl());
        RefreshToken refreshToken = RefreshToken.issue(
                user,
                refreshTokenHash,
                tokenFamilyId,
                refreshTokenExpiresAt,
                clientIpHasher.hash(clientIp),
                normalizeUserAgent(userAgent)
        );

        refreshTokenRepository.save(refreshToken);
        user.recordLogin(accessToken.issuedAt());
        LoginResponse response = LoginResponse.of(
                user,
                accessToken
        );

        return new LoginResult(
                response,
                rawRefreshToken
        );
    }

    private void validateAccountStatus(UserStatus status) {
        switch (status) {
            case ACTIVE -> {/* 로그인 계속 진행 */}
            case LOCKED -> {
                throw new ApiException(AuthErrorCode.ACCOUNT_LOCKED);
            }
            case WITHDRAWN -> {
                throw new ApiException(UserErrorCode.ACCOUNT_WITHDRAWN);
            }
        }
    }


    private String normalizeUserAgent(String userAgent) {
        if(userAgent == null || userAgent.isBlank()) {
            return null;
        }

        String normalized = userAgent.trim();
        int codePointCount = normalized.codePointCount(0, normalized.length());

        if (codePointCount <= MAX_USER_AGENT_CODE_POINTS) {
            return normalized;
        }

        int endIndex = normalized.offsetByCodePoints(0, MAX_USER_AGENT_CODE_POINTS);

        return normalized.substring(0, endIndex);
    }
}
