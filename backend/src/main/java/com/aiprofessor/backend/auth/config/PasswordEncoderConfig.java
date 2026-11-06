package com.aiprofessor.backend.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;

// proxyBeanMethods = false -> 설정 클래스 안의 @Bean 메서드끼리 직접 호출해 싱글톤을 보장할 필요가 없을 때 사용하는 설정
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfig {

    private static final String BCRYPT_ID = "bcrypt";
    private static final int BCRYPT_STRENGTH = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = new HashMap<>();

        encoders.put(
                BCRYPT_ID,
                new BCryptPasswordEncoder(BCRYPT_STRENGTH)
        );

        return new DelegatingPasswordEncoder(
                BCRYPT_ID,
                encoders
        );
    }
}
