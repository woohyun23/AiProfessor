package com.aiprofessor.backend.auth.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class ClientIpHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final SecretKeySpec secretKey;

    public ClientIpHasher(@Value("${aiprofessor.auth.ip-hash-secret}") String secret) {

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);

        if (secretBytes.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException("Secret must be at least 32 bytes long");
        }

        this.secretKey = new SecretKeySpec(secretBytes, HMAC_ALGORITHM);
    }

    public String hash(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return null;

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(secretKey);

            byte[] digest = mac.doFinal(clientIp.trim().getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Client IP could not be hashed", exception);
        }
    }
}
