package com.aiprofessor.backend.auth.security;

import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.support.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@RequiredArgsConstructor
public class CurrentAccountStatusFilter extends OncePerRequestFilter {
    // SecurityConfig에서 수동으로 Filter Chain에 등록하기 때문에 @Component로 자동 등록시킬 필요 X

    private static final RequestMatcher PUBLIC_REQUESTS =
            new OrRequestMatcher(
                    PathPatternRequestMatcher.pathPattern(
                            HttpMethod.POST,
                            "/api/v1/users"
                    ),
                    PathPatternRequestMatcher.pathPattern(
                            HttpMethod.POST,
                            "/api/v1/auth/login"
                    ),
                    PathPatternRequestMatcher.pathPattern(
                            HttpMethod.GET,
                            "/actuator/health"
                    )
    );

    private final CurrentAccountStatusValidator accountStatusValidator;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_REQUESTS.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        // 공개 API 또는 Access Token 인증 전 요청은 상태 조회 X
        if (!(authentication
                instanceof JwtAuthenticationToken jwtAuthentication)
        || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);

            return;
        }

        try {
            UUID userId = extractUserId(jwtAuthentication);
            accountStatusValidator.validate(userId);
        } catch (ApiException exception) {
            authenticationEntryPoint.commenceBearerFailure(
                    request,
                    response,
                    exception.getErrorCode()
            );

            return;
        }

        filterChain.doFilter(request, response);
    }

    private UUID extractUserId(JwtAuthenticationToken authentication) {
        String subject = authentication
                .getToken()
                .getSubject();

        if (subject == null) {
            throw new ApiException(AuthErrorCode.INVALID_ACCESS_TOKEN);
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(AuthErrorCode.INVALID_ACCESS_TOKEN);
        }
    }
}
