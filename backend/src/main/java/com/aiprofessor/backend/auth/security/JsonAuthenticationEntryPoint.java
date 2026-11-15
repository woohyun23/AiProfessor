package com.aiprofessor.backend.auth.security;

import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.support.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String EXPIRED_DESCRIPTION_PREFIX = "Jwt expired at ";
    private final SecurityErrorResponseWriter responseWriter;
    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        // Spring Security 기본 Handler → HTTP 상태와 WWW-Authenticate 헤더 설정 → AiProfessor Writer → 공통 JSON 오류 본문 작성
        // RFC 6750에 맞는 WWW-Authenticate 응답 헤더 유지
        // WWW-Authenticate 헤더 - 서버가 클라이언트에게 어떤 인증 방식이 필요하며, 인증이 왜 실패했는지 알려주는 HTTP 응답 헤더로, 인증 실패를 표준 방식으로 해석할 수 있도록 제공하는 헤더
        // delegate를 먼저 호출하는 이유 - 직접 JSON만 작성하면 OAuth2 Resource Server의 표준 응답 헤더가 사라질 수 있기 때문
        delegate.commence(request, response, exception);

        responseWriter.write(
                response,
                resolveErrorCode(exception)
        );
    }

    private AuthErrorCode resolveErrorCode(AuthenticationException exception) {
        if (isExpiredAccessToken(exception)) {
            return AuthErrorCode.EXPIRED_ACCESS_TOKEN;
        }
        // instanceof는 객체가 특정 클래스 또는 인터페이스 타입인지 실행 중에 확인하는 Java 연산자
        // 토큰이 없는 상황을 다른 인증 실패와 구분하기 위해 사용
        if (exception instanceof InsufficientAuthenticationException) {
            return AuthErrorCode.AUTHENTICATION_REQUIRED;
        }

        return AuthErrorCode.INVALID_ACCESS_TOKEN;
    }

    private boolean isExpiredAccessToken(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof JwtValidationException exception) {
                return exception.getErrors()
                        .stream()
                        .map(OAuth2Error::getDescription)
                        .filter(Objects::nonNull)
                        .anyMatch(description ->
                                description.startsWith(EXPIRED_DESCRIPTION_PREFIX)
                        );
            }

            current = current.getCause();
        }

        return false;
    }

    public void commenceBearerFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode
    ) throws IOException {
        BearerTokenError bearerTokenError = new BearerTokenError(
                BearerTokenErrorCodes.INVALID_TOKEN,
                HttpStatus.UNAUTHORIZED,
                null,
                null
        );

        delegate.commence(
                request,
                response,
                new OAuth2AuthenticationException(bearerTokenError)
        );

        responseWriter.write(response, errorCode);
    }

}
