package com.aiprofessor.backend.auth.error;

import com.aiprofessor.backend.support.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements ErrorCode {

    INVALID_CREDENTIALS("AUTH_0001", "이메일 또는 비밀번호가 일치하지 않습니다.", HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED("AUTH_0002", "잠금 처리된 계정입니다.", HttpStatus.UNAUTHORIZED);

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
