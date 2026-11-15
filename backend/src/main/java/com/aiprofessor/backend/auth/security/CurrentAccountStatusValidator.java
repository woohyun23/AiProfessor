package com.aiprofessor.backend.auth.security;

import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.auth.error.UserErrorCode;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.support.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CurrentAccountStatusValidator {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    // 사용자 ID 입력 → DB 사용자 조회 → 현재 상태 판정 → 정상 반환 또는 ApiException 발생
    public void validate(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ApiException(AuthErrorCode.INVALID_ACCESS_TOKEN)
                );

        // 유효한 JWT의 사용자 존재 여부를 외부에 노출하지 않기 위해 사용자가 없는 경우 404에러로 반환 X
        switch (user.getStatus()) {
            case ACTIVE -> { /* 정상 계정이므로 보호 API 요청 처리 */ }
            case LOCKED -> throw new ApiException(AuthErrorCode.ACCOUNT_LOCKED);
            case WITHDRAWN -> throw new ApiException(UserErrorCode.ACCOUNT_WITHDRAWN);
        }
    }
}
