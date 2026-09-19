package com.example.backend.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwtService =
            new JwtService("test-secret-key-please-make-it-at-least-32-chars-long", 3600000);

    @Test
    void generatesTokenAndExtractsClaims() {
        String token = jwtService.generateToken(42L, "CUSTOMER");

        assertThat(jwtService.extractUserId(token)).isEqualTo(42L);
        assertThat(jwtService.extractRole(token)).isEqualTo("CUSTOMER");
        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    void generatesUniqueJtiPerToken() {
        String first = jwtService.generateToken(1L, "CUSTOMER");
        String second = jwtService.generateToken(1L, "CUSTOMER");

        assertThat(jwtService.extractJti(first)).isNotBlank().isNotEqualTo(jwtService.extractJti(second));
    }

    @Test
    void rejectsExpiredToken() {
        JwtService shortLived = new JwtService("test-secret-key-please-make-it-at-least-32-chars-long", 1);
        String token = shortLived.generateToken(1L, "CUSTOMER");

        await(token, shortLived);
    }

    private void await(String token, JwtService service) {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(service.isTokenValid(token)).isFalse();
    }

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        JwtService otherService = new JwtService("different-secret-key-that-is-also-32-chars-plus", 3600000);
        String token = otherService.generateToken(1L, "CUSTOMER");

        assertThat(jwtService.isTokenValid(token)).isFalse();
    }
}
