package com.aiprofessor.backend.support.error;

import org.springframework.http.HttpStatusCode;

public interface ErrorCode {

    String code();
    String message();
    HttpStatusCode statusCode();
}
