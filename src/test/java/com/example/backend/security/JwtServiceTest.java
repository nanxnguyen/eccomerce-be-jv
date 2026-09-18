package com.example.backend.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwtService =
            new JwtService("test-secret-key-please-make-it-at-least-32-chars-long", 3600000);

    @Test
    void generatesTokenAndExtractsClaims() {
        String token = jwtService.generateToken("user@example.com", "CUSTOMER");

        assertThat(jwtService.extractEmail(token)).isEqualTo("user@example.com");
        assertThat(jwtService.extractRole(token)).isEqualTo("CUSTOMER");
        assertThat(jwtService.isTokenValid(token, "user@example.com")).isTrue();
        assertThat(jwtService.isTokenValid(token, "other@example.com")).isFalse();
    }

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        JwtService otherService = new JwtService("different-secret-key-that-is-also-32-chars-plus", 3600000);
        String token = otherService.generateToken("user@example.com", "CUSTOMER");

        assertThat(jwtService.isTokenValid(token, "user@example.com")).isFalse();
    }
}
