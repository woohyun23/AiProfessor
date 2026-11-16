package com.aiprofessor.backend.auth.controller;

import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import com.aiprofessor.backend.auth.dto.request.LoginRequest;
import com.aiprofessor.backend.auth.dto.response.LoginResponse;
import com.aiprofessor.backend.auth.dto.response.RefreshResponse;
import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.auth.service.LoginResult;
import com.aiprofessor.backend.auth.service.LoginService;
import com.aiprofessor.backend.auth.service.RefreshRotationResult;
import com.aiprofessor.backend.auth.service.RefreshTokenRotationService;
import com.aiprofessor.backend.support.error.ApiException;
import com.aiprofessor.backend.support.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    private static final String REFRESH_TOKEN_COOKIE_PATH = "/api/v1/auth";
    private static final String SAME_SITE_POLICY = "Lax";

    private final LoginService loginService;
    private final AuthTokenProperties authTokenProperties;
    private final Environment environment;
    private final RefreshTokenRotationService refreshTokenRotationService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        LoginResult result = loginService.login(
                request,
                servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT)
        );

        ResponseCookie refreshTokenCookie = createRefreshTokenCookie(
                result.refreshToken(),
                authTokenProperties.refreshTokenTtl()
        );

        return ResponseEntity
                .ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshTokenCookie.toString()
                )
                .body(
                        ApiResponse.success("로그인에 성공했습니다.", result.response())
                );
    }

    private ResponseCookie createRefreshTokenCookie(String refreshToken, Duration maxAge) {
        boolean secure = !environment.matchesProfiles("local & !prod");

        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite(SAME_SITE_POLICY)
                .path(REFRESH_TOKEN_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) String rawRefreshToken,
            HttpServletResponse servletResponse
    ) {
        RefreshRotationResult result;
        try {
            result = refreshTokenRotationService.rotate(rawRefreshToken);
        } catch (ApiException exception) {
            if (exception.getErrorCode() == AuthErrorCode.INVALID_REFRESH_TOKEN
                    || exception.getErrorCode() == AuthErrorCode.REFRESH_TOKEN_EXPIRED
                    || exception.getErrorCode() == AuthErrorCode.REFRESH_TOKEN_REUSED) {
                servletResponse.addHeader(
                        HttpHeaders.SET_COOKIE,
                        createRefreshTokenCookie("", Duration.ZERO).toString()
                );
            }
            throw exception;
        }

        ResponseCookie cookie = createRefreshTokenCookie(
                result.refreshToken(),
                authTokenProperties.refreshTokenTtl()
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.success(
                        "Access Token을 갱신했습니다.",
                        RefreshResponse.from(result.accessToken())
                ));
    }
}
