package com.aiprofessor.backend.support.error;

import org.springframework.http.HttpStatus;

public enum CommonErrorCode implements ErrorCode {

    INVALID_REQUEST("COMMON_0001", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST("COMMON_0002", "요청 본문을 읽을 수 없습니다.", HttpStatus.BAD_REQUEST),
    METHOD_NOT_ALLOWED("COMMON_0003", "지원하지 않는 HTTP 메서드입니다.", HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE("COMMON_0004", "지원하지 않는 미디어 타입입니다.", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    INTERNAL_SERVER_ERROR("COMMON_0005", "서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus statusCode;

    CommonErrorCode(String code, String message, HttpStatus statusCode) {
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
