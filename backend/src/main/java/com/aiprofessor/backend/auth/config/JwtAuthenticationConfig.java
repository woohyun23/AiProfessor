package com.aiprofessor.backend.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

@Configuration(proxyBeanMethods = false)
public class JwtAuthenticationConfig {

    private static final String ROLE_CLAIM = "role";
    private static final String ROLE_PREFIX = "ROLE_";

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();

        authoritiesConverter.setAuthoritiesClaimName(ROLE_CLAIM);
        authoritiesConverter.setAuthorityPrefix(ROLE_PREFIX);

        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();

        // JWT의 sub를 이증 사용자 이름으로 사용
        authenticationConverter.setPrincipalClaimName(JwtClaimNames.SUB);
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        return authenticationConverter;
    }
}
