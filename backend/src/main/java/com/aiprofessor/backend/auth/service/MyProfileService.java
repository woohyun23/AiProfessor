package com.aiprofessor.backend.auth.service;

import com.aiprofessor.backend.auth.domain.User;
import com.aiprofessor.backend.auth.dto.response.MyProfileResponse;
import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.auth.repository.UserRepository;
import com.aiprofessor.backend.support.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MyProfileService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile(UUID authenticatedUserId) {
        return MyProfileResponse.from(findAuthenticatedUser(authenticatedUserId));
    }

    @Transactional
    public MyProfileResponse updateMyProfile(UUID authenticatedUserId, String displayName) {
        User user = findAuthenticatedUser(authenticatedUserId);
        user.changeDisplayName(displayName);

        return MyProfileResponse.from(user);
    }

    private User findAuthenticatedUser(UUID authenticatedUserId) {
        return userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> new ApiException(AuthErrorCode.INVALID_ACCESS_TOKEN));
    }
}
