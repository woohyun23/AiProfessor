package com.aiprofessor.backend.support.error;

public record FieldErrorResponse(
        String field,
        String message
) {
}
