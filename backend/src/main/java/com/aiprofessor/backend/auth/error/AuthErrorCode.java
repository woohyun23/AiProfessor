package com.aiprofessor.backend.auth.error;

import com.aiprofessor.backend.support.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements ErrorCode {

    INVALID_CREDENTIALS("AUTH_0001", "이메일 또는 비밀번호가 일치하지 않습니다.", HttpStatus.UNAUTHORIZED),
    INVALID_ACCESS_TOKEN("AUTH_0002", "유효하지 않은 Access Token입니다.", HttpStatus.UNAUTHORIZED),
    EXPIRED_ACCESS_TOKEN("AUTH_0003", "만료된 Access Token입니다.", HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED("AUTH_0007", "잠금 처리된 계정입니다.", HttpStatus.UNAUTHORIZED),
    AUTHENTICATION_REQUIRED("AUTH_0008", "인증이 필요한 요청입니다.", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED("AUTH_0009", "요청한 기능에 접근할 권한이 없습니다.", HttpStatus.FORBIDDEN);

    private final String code;
    private final String message;
    private final HttpStatus statusCode;

    AuthErrorCode(
            String code,
            String message,
            HttpStatus statusCode
    ) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public HttpStatus statusCode() {
        return statusCode;
    }
}
