package com.aiprofessor.backend.support.error;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(
            ApiException exception
    ) {
        ErrorCode errorCode = exception.getErrorCode();

        ErrorResponse response = ErrorResponse.from(
                errorCode,
                exception.getMessage()
        );

        return ResponseEntity
                .status(errorCode.statusCode())
                .body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        List<FieldErrorResponse> errors =
                exception.getBindingResult()
                        .getFieldErrors()
                        .stream()
                        .map(fieldError -> new FieldErrorResponse(
                                fieldError.getField(),
                                fieldError.getDefaultMessage()
                        ))
                        .toList();

        ErrorCode errorCode = CommonErrorCode.INVALID_REQUEST;

        return ResponseEntity
                .status(errorCode.statusCode())
                .body(ErrorResponse.from(errorCode, errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception
    ) {
        List<FieldErrorResponse> errors =
                exception.getConstraintViolations()
                        .stream()
                        .map(violation -> new FieldErrorResponse(
                                violation.getPropertyPath().toString(),
                                violation.getMessage()
                        ))
                        .toList();

        ErrorCode errorCode = CommonErrorCode.INVALID_REQUEST;

        return ResponseEntity
                .status(errorCode.statusCode())
                .body(ErrorResponse.from(errorCode, errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(
            HttpMessageNotReadableException exception
    ) {
        return createErrorResponse(
                CommonErrorCode.MALFORMED_REQUEST
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception
    ) {
        return createErrorResponse(
                CommonErrorCode.METHOD_NOT_ALLOWED
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception
    ) {
        return createErrorResponse(
                CommonErrorCode.UNSUPPORTED_MEDIA_TYPE
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception exception
    ) {
        log.error("Unexpected server error", exception);

        return createErrorResponse(
                CommonErrorCode.INTERNAL_SERVER_ERROR
        );
    }

    private ResponseEntity<ErrorResponse> createErrorResponse(
            ErrorCode errorCode
    ) {
        return ResponseEntity
                .status(errorCode.statusCode())
                .body(ErrorResponse.from(errorCode));
    }
}