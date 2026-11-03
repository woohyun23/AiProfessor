package com.aiprofessor.backend.support.error;

import java.util.Objects;

public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(
                Objects.requireNonNull(
                        errorCode,
                        "errorCode must not be null"
                ).message()
        );
        this.errorCode = errorCode;
    }

    public ApiException(
            ErrorCode errorCode,
            String message
    ) {
        super(message);
        this.errorCode = Objects.requireNonNull(
                errorCode
                , "errorCode must not be null"
        );
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
