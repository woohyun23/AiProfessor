package com.aiprofessor.backend.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Objects;

@Configuration(proxyBeanMethods = false)
public class JwtKeyConfig {

    private static final int RSA_KEY_SIZE = 2048;

    @Bean
    // JWT 공통 설정
    // RSA KeyPairGenerator 준비 -> 키 크기를 2048비트로 설정 -> 공개 키와 개인 키 생성 -> KeyPair Bean 등록 ->JwtEncoder에 주입
    public JwtEncoder jwtEncoder(
            KeyPair jwtSigningKeyPair,
            AuthTokenProperties authTokenProperties
    ) {
        RSAPublicKey publicKey = (RSAPublicKey) jwtSigningKeyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) jwtSigningKeyPair.getPrivate();

        // JWT 헤더에 들어갈 kid 식별자 생성 -> 키 교체 시, 서버는 kid르 확인해 어던 공개 키로 검증해야 하는지 판단
        return NimbusJwtEncoder
                // 인코더에서 사용할 RSA 키 지정
                .withKeyPair(publicKey, privateKey)
                // SHA-256과 RSA 개인 키로 서명
                .algorithm(SignatureAlgorithm.RS256)
                // 사용할 키의 식별자 지정
                .jwkPostProcessor(builder -> builder.keyID(authTokenProperties.keyId())
                ).build();
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("local")
    // local 실행 시의 RSA 키 쌍 생성
    // 애플리케이션 실행 -> 기본 local 프로필 활성화 -> LocalJwtKeyConfig 활성화 -> RSA 2048비트 KeyPair 생성 -> KeyPair Bean 등록
    // -> JwtKeyConfig가 KeyPair 주입 -> RS256 JwtEncoder 생성 -> AccessTokenGenerator에서 JwtEncoder 사용
    static class LocalJwtKeyConfig {

        @Bean
        public KeyPair jwtSigningKeyPair() {
            try {
                KeyPairGenerator keyPairGenerator = java.security.KeyPairGenerator.getInstance("RSA");
                keyPairGenerator.initialize(RSA_KEY_SIZE);

                return keyPairGenerator.generateKeyPair();
            } // 애플리케이션 재시작 시, 새로운 키 생성 -> 재시작 전에 발급된 Access Token 으로 새 공개키 검증 불가능
            catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("RSA algorithm is not available", exception);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("prod")
    // 외부 PEM 파일에서 RSA 키 상 로딩
    // SPRING_PROFILES_ACTIVE=prod -> ProductionJwtKeyConfig 활성화 -> 환경변수에서 키 파일 경로 확인 -> 개인 키 PKCS#8 변환
    // -> 공개 키 X.509 변환 -> KeyPair Bean 등록 -> RS256 JwtEncoder 생성 -> 고정 키로 Access Token 서명
    static class ProductionJwtKeyConfig {

        @Bean
        public KeyPair jwtSigningKeyPair(
                @Value("${aiprofessor.auth.token.private-key-location}") Resource privateKeyResource,
                @Value("${aiprofessor.auth.token.public-key-location}") Resource publicKeyResource
        ) {
            try (
                    InputStream privateKeyInputStream =
                            privateKeyResource.getInputStream();
                    InputStream publicKeyInputStream =
                            publicKeyResource.getInputStream()
            ) {
                // PEM 파일 변환 - 개인 키 : PKCS#8 형식, 공개 키 : X.509 형식
                RSAPrivateKey privateKey = Objects.requireNonNull(
                        RsaKeyConverters.pkcs8()
                                .convert(privateKeyInputStream)
                );

                RSAPublicKey publicKey = Objects.requireNonNull(
                        RsaKeyConverters.x509()
                                .convert(publicKeyInputStream)
                );

                return new KeyPair(publicKey, privateKey);
            } catch (IOException exception) {
                throw new IllegalStateException("JWT RSA key files could not be loaded", exception);
            }
        }
    }
}
