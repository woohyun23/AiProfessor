package com.aiprofessor.backend.auth.controller;

import com.aiprofessor.backend.auth.dto.request.SignUpRequest;
import com.aiprofessor.backend.auth.dto.response.SignUpResponse;
import com.aiprofessor.backend.auth.service.SignUpService;
import com.aiprofessor.backend.support.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    private final SignUpService signUpService;

    @PostMapping
    public ResponseEntity<ApiResponse<SignUpResponse>> signUp(
            @Valid @RequestBody SignUpRequest request
    ) {
        SignUpResponse response = signUpService.signUp(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("회원가입에 성공했습니다.", response));
    }
}
