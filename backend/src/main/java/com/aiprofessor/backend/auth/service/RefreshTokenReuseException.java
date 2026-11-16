package com.aiprofessor.backend.auth.service;

import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.support.error.ApiException;

public class RefreshTokenReuseException extends ApiException {

    RefreshTokenReuseException() {
        super(AuthErrorCode.REFRESH_TOKEN_REUSED);
    }
}
