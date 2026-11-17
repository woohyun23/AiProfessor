package com.aiprofessor.backend.auth.service;

import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.dto.request.ChangePasswordRequest;
import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.auth.error.UserErrorCode;
import com.aiprofessor.backend.auth.repository.RefreshTokenRepository;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.support.error.ApiException;
import com.aiprofessor.backend.support.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordChangeService {

    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void changePassword(UUID authenticatedUserId, ChangePasswordRequest request) {
        User user = userRepository.findByIdForUpdate(authenticatedUserId)
                .orElseThrow(() -> new ApiException(AuthErrorCode.INVALID_ACCESS_TOKEN));

        switch (user.getStatus()) {
            case ACTIVE -> { }
            case LOCKED -> throw new ApiException(AuthErrorCode.ACCOUNT_LOCKED);
            case WITHDRAWN -> throw new ApiException(UserErrorCode.ACCOUNT_WITHDRAWN);
        }

        if (exceedsBcryptLimit(request.currentPassword())
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())
        ) {
            throw new ApiException(UserErrorCode.CURRENT_PASSWORD_MISMATCH);
        }

        if (request.currentPassword().equals(request.newPassword())) {
            throw new ApiException(UserErrorCode.SAME_PASSWORD);
        }

        if (exceedsBcryptLimit(request.newPassword())) {
            throw new ApiException(
                    CommonErrorCode.INVALID_REQUEST,
                    "새 비밀번호는 UTF-8 기준 72바이트 이하여야 합니다."
            );
        }

        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokenRepository.revokeActiveByUserId(authenticatedUserId, clock.instant());
    }

    private boolean exceedsBcryptLimit(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }
}
