package com.aiprofessor.backend.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final String SIGN_UP_PATH = "/api/v1/users";
    private static final String LOGIN_PATH = "/api/v1/auth/login";

    // Bearer Access Token을 검증하고 JWT Claim을 Spring Security 인증 객체로 변환
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {
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
                .oauth2ResourceServer(resourceServer ->
                        resourceServer.jwt(jwt ->
                                // 기존 JwtDecoder로 토큰을 검증하고, Claim을 인증 사용자와 권한으로 변환
                                jwt.jwtAuthenticationConverter(
                                        jwtAuthenticationConverter
                                )
                        )
                );

        return http.build();
    }
}
