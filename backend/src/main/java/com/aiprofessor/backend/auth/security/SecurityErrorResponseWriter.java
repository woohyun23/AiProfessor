package com.aiprofessor.backend.auth.security;

import com.aiprofessor.backend.support.error.ErrorCode;
import com.aiprofessor.backend.support.error.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {

    // Spring Security의 인증 실패는 Controller 실행 이전 보안 필터에서 발생하기 때문에
    // AuthenticationEntryPoint, AccessDeniedHandler의 직접 응답 작성 필요
    private final ObjectMapper objectMapper;

    public void write(
            HttpServletResponse response,
            ErrorCode errorCode
    ) throws IOException {
        response.setStatus(errorCode.statusCode().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.from(errorCode)
        );
    }
}
