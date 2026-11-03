package com.aiprofessor.backend.support.error;

import java.util.List;

public record ErrorResponse(
        boolean success,
        String errorCode,
        String message,
        List<FieldErrorResponse> errors
) {

    public ErrorResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ErrorResponse from (ErrorCode errorCode) {
        return new ErrorResponse(
                false,
                errorCode.code(),
                errorCode.message(),
                List.of()
        );
    }

    public static ErrorResponse from (
            ErrorCode errorCode,
            String message
    ) {
        return new ErrorResponse(
                false,
                errorCode.code(),
                message,
                List.of()
        );
    }

    public static ErrorResponse from (
            ErrorCode errorCode,
            List<FieldErrorResponse> errors
    ) {
        return new ErrorResponse(
                false,
                errorCode.code(),
                errorCode.message(),
                errors
        );
    }
}
