package com.aiprofessor.backend.auth.config;

import com.aiprofessor.backend.auth.error.AuthErrorCode;
import com.aiprofessor.backend.auth.security.*;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class RefreshSecurityConfig {

    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String LOGOUT_PATH = "/api/v1/auth/logout";
    private static final String LOGIN_PATH = "/api/v1/auth/login";

    @Bean
    @Order(0)
    SecurityFilterChain loginSecurityFilterChain(
            HttpSecurity http,
            SecurityErrorResponseWriter responseWriter,
            @Value("${aiprofessor.auth.login.allowed-origins:}") String configuredOrigins
    ) throws Exception {
        return configureCookieEndpoint(http, LOGIN_PATH, responseWriter, configuredOrigins)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain refreshSecurityFilterChain(
            HttpSecurity http,
            SecurityErrorResponseWriter responseWriter,
            @Value("${aiprofessor.auth.refresh.allowed-origins:}") String configuredOrigins
    ) throws Exception {
        return configureCookieEndpoint(http, REFRESH_PATH, responseWriter, configuredOrigins)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain logoutSecurityFilterChain(
            HttpSecurity http,
            SecurityErrorResponseWriter responseWriter,
            @Value("${aiprofessor.auth.logout.allowed-origins:}") String configuredOrigins,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler,
            CurrentAccountStatusValidator accountStatusValidator
    ) throws Exception {
        return configureCookieEndpoint(http, LOGOUT_PATH, responseWriter, configuredOrigins)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .addFilterAfter(
                        new CurrentAccountStatusFilter(accountStatusValidator, authenticationEntryPoint),
                        BearerTokenAuthenticationFilter.class
                ).build();
    }

    private HttpSecurity configureCookieEndpoint(
            HttpSecurity http,
            String path,
            SecurityErrorResponseWriter responseWriter,
            String configuredOrigins
    ) {
        List<String> allowedOrigins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of(HttpMethod.POST.name()));
        cors.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION));
        cors.setAllowCredentials(true);
        cors.validateAllowCredentials();

        return http
                .securityMatcher(new OrRequestMatcher(
                        PathPatternRequestMatcher.pathPattern(HttpMethod.POST, path),
                        PathPatternRequestMatcher.pathPattern(HttpMethod.OPTIONS, path)
                ))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(config -> config.configurationSource(request -> cors))
                .addFilterBefore(
                        new RefreshOriginFilter(allowedOrigins, responseWriter),
                        CorsFilter.class
                )
                // 이 체인의 갱신 요청은 아래 Origin 필터에서 검증
                .csrf(AbstractHttpConfigurer::disable);
    }

    private static final class RefreshOriginFilter extends OncePerRequestFilter {

        private final List<String> allowedOrigins;
        private final SecurityErrorResponseWriter responseWriter;


        private RefreshOriginFilter(
                List<String> allowedOrigins,
                SecurityErrorResponseWriter responseWriter
        ) {
            this.allowedOrigins = allowedOrigins;
            this.responseWriter = responseWriter;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException {
            List<String> origins = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));

            if (origins.size() != 1
                    || "null".equals(origins.getFirst())
                    || !allowedOrigins.contains(origins.getFirst())) {
                responseWriter.write(response, AuthErrorCode.ACCESS_DENIED);
                return;
            }

            filterChain.doFilter(request, response);
        }
    }
}
