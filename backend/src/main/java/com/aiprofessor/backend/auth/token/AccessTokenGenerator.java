package com.aiprofessor.backend.auth.token;

import com.aiprofessor.backend.auth.config.AuthTokenProperties;
import com.aiprofessor.backend.auth.domain.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AccessTokenGenerator {

    private static final String JWT_HEADER_TYPE = "JWT";
    private static final String ACCESS_TOKEN_TYPE = "access";

    private final JwtEncoder jwtEncoder;
    private final AuthTokenProperties authTokenProperties;
    private final Clock clock;

    // 사용자 UUID·역할 전달 -> 입력값 검증 -> UTC 발급 시각과 만료 시각 계산 -> 토큰별 jti 생성
    // -> JWT Header 생성 -> JWT Claim 생성 -> JwtEncoder로 RS256 서명 -> IssuedAccessToken 반환
    public IssuedAccessToken generate(
            UUID userId,
            UserRole role
    ) {
        // 필수 입력 검사
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(role, "role must not be null");

        // 발급, 만료 시각 계산
        // Instant.now()가 아닌 Clock을 사용한 이유 - 테스트에서 고정된 시각 주입 가능하기 대문
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(authTokenProperties.accessTokenTtl());

        // 토큰 식별자 생성, 항상 새로 생성
        String jwtId = UUID.randomUUID().toString();

        // JWT 헤더 생성
        JwsHeader header = JwsHeader
                .with(SignatureAlgorithm.RS256)
                .type(JWT_HEADER_TYPE)
                .keyId(authTokenProperties.keyId())
                .build();

        // Payload claim 생성
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(authTokenProperties.issuer())
                .audience(List.of(authTokenProperties.audience()))
                .subject(userId.toString())
                .claim("role", role.name())
                .id(jwtId)
                .claim("typ", ACCESS_TOKEN_TYPE)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        String tokenValue =jwtEncoder
                .encode(
                        JwtEncoderParameters.from(
                                header,
                                claims
                        )
                ).getTokenValue();

        return new IssuedAccessToken(
                tokenValue,
                issuedAt,
                expiresAt
        );
    }
}
