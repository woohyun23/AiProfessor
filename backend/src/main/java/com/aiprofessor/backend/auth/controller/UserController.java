package com.aiprofessor.backend.auth.controller;

import com.aiprofessor.backend.auth.dto.request.SignUpRequest;
import com.aiprofessor.backend.auth.dto.request.UpdateDisplayNameRequest;
import com.aiprofessor.backend.auth.dto.response.MyProfileResponse;
import com.aiprofessor.backend.auth.dto.response.SignUpResponse;
import com.aiprofessor.backend.auth.service.MyProfileService;
import com.aiprofessor.backend.auth.service.SignUpService;
import com.aiprofessor.backend.support.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    private final SignUpService signUpService;
    private final MyProfileService myProfileService;

    @PostMapping
    public ResponseEntity<ApiResponse<SignUpResponse>> signUp(
            @Valid @RequestBody SignUpRequest request
    ) {
        SignUpResponse response = signUpService.signUp(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("회원가입에 성공했습니다.", response));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MyProfileResponse>> getMyProfile(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(ApiResponse.success(
                "내 정보를 조회했습니다.",
                myProfileService.getMyProfile(userId)
        ));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<MyProfileResponse>> updateMyProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateDisplayNameRequest request
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(ApiResponse.success(
                "표시할 이름을 변경했습니다.",
                myProfileService.updateMyProfile(userId, request.displayName())
        ));
    }
}
