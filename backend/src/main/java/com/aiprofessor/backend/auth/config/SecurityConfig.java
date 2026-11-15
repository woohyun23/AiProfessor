package com.aiprofessor.backend.auth.config;

import com.aiprofessor.backend.auth.security.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final String SIGN_UP_PATH = "/api/v1/users";
    private static final String LOGIN_PATH = "/api/v1/auth/login";

    // Bearer Access Token을 검증하고 JWT Claim을 Spring Security 인증 객체로 변환
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler,
            CurrentAccountStatusValidator accountStatusValidator,
            SecurityErrorResponseWriter responseWriter
    ) throws Exception {

        CurrentAccountStatusFilter accountStatusFilter = new CurrentAccountStatusFilter(
                accountStatusValidator,
                authenticationEntryPoint
        );

        http
                .csrf(csrf -> csrf
                        // 공개 JSON API는 CSRF Token 검사에서 제외
                        // Cookie를 사용하는 refresh/logout은 별도 요청 보호가 필요하므로 포함
                        .ignoringRequestMatchers(
                                SIGN_UP_PATH,
                                LOGIN_PATH
                        )
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                // 서버 세션을 만들지 않고, 요청마다 Bearer Access Token으로 인증
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(authorize -> authorize
                        // 회원가입과 로그인은 Access Token 없이 접근
                        .requestMatchers(
                                HttpMethod.POST,
                                SIGN_UP_PATH,
                                LOGIN_PATH
                        )
                        .permitAll()
                        .requestMatchers("/actuator/health")
                        .permitAll()
                        .anyRequest()
                        .authenticated()
                )
                .exceptionHandling(exception -> exception
                        // 토큰이 없거나 인증되지 않은 사용자의 보호 API 접근
                        .authenticationEntryPoint(authenticationEntryPoint)
                        // 인증은 성공했지만, 필요한 권한이 없는 요청
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .oauth2ResourceServer(resourceServer ->
                        resourceServer
                                // Bearer Token 파싱, 검증 단계의 인증 실패
                                .authenticationEntryPoint(authenticationEntryPoint)
                                .accessDeniedHandler(accessDeniedHandler)
                                .jwt(jwt ->
                                        jwt.jwtAuthenticationConverter(
                                                jwtAuthenticationConverter
                                        )
                        )
                )
                .addFilterAfter(
                        accountStatusFilter,
                        BearerTokenAuthenticationFilter.class
                );

        return http.build();
    }
}
