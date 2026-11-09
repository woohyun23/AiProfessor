package com.aiprofessor.backend.auth.error;

import com.aiprofessor.backend.support.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum UserErrorCode implements ErrorCode {

    EMAIL_ALREADY_EXISTS("USER_0001", "이미 가입된 이메일입니다.", HttpStatus.CONFLICT),
    ACCOUNT_WITHDRAWN("USER_0002", "탈퇴 처리된 계정입니다.", HttpStatus.UNAUTHORIZED);

    private final String code;
    private final String message;
    private final HttpStatus statusCode;

    UserErrorCode(
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
