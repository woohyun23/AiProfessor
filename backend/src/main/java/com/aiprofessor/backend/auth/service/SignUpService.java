package com.aiprofessor.backend.auth.service;

import com.aiprofessor.backend.auth.domain.EmailNormalizer;
import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.dto.request.SignUpRequest;
import com.aiprofessor.backend.auth.dto.response.SignUpResponse;
import com.aiprofessor.backend.auth.error.UserErrorCode;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.support.error.ApiException;
import com.aiprofessor.backend.support.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class SignUpService {

    private static final int BCRYPT_MAX_BYTES = 72;
    private static final String EMAIL_UNIQUE_CONSTRAINT = "uk_users_email";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailNormalizer emailNormalizer;

    @Transactional
    public SignUpResponse signUp(SignUpRequest request) {
        String normalizedEmail =
                emailNormalizer.normalize(request.email());

        validateDuplicateEmail(normalizedEmail);
        validateBcryptInputLength(request.password());

        String encodedPassword = passwordEncoder.encode(request.password());

        User user = User.create(
                normalizedEmail,
                encodedPassword,
                request.displayName()
        );

        try {
            User savedUser = userRepository.saveAndFlush(user);

            return SignUpResponse.from(savedUser);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueConstraintViolation(exception)) {
                throw new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS);
            }

            throw exception;
        }
    }

    private void validateDuplicateEmail(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    private void validateBcryptInputLength(String password) {
        int byteLength = password.getBytes(StandardCharsets.UTF_8).length;

        if (byteLength > BCRYPT_MAX_BYTES) {
            throw new ApiException(
                    CommonErrorCode.INVALID_REQUEST,
                    "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다."
            );
        }
    }

    private boolean isEmailUniqueConstraintViolation(Throwable exception) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation) {
                return EMAIL_UNIQUE_CONSTRAINT.equals(violation.getConstraintName());
            }

            cause = cause.getCause();
        }

        return false;
    }
}
