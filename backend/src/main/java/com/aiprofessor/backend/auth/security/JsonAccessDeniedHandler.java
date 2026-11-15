package com.aiprofessor.backend.auth.security;

import com.aiprofessor.backend.auth.error.AuthErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter responseWriter;
    private final BearerTokenAccessDeniedHandler delegate = new BearerTokenAccessDeniedHandler();

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException {
        // OAuth2 Resource Server의 표준 WWW-Authenticate 헤더 유지
        delegate.handle(request, response, exception);

        responseWriter.write(
                response,
                AuthErrorCode.ACCESS_DENIED
        );
    }
}
