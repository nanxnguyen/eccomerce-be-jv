package com.example.backend.security;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KeycloakBearerTokenResolverTest {

    private final KeycloakBearerTokenResolver resolver = new KeycloakBearerTokenResolver(
            new ObjectMapper(), "http://localhost:8180/realms/ecommerce");

    @Test
    void passesKeycloakTokensToResourceServer() {
        String token = token("http://localhost:8180/realms/ecommerce");
        MockHttpServletRequest request = request(token);

        assertEquals(token, resolver.resolve(request));
    }

    @Test
    void leavesLegacyApplicationTokensForExistingJwtFilter() {
        MockHttpServletRequest request = request(token(null));

        assertNull(resolver.resolve(request));
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static String token(String issuer) {
        String claims = issuer == null ? "{}" : "{\"iss\":\"" + issuer + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }
}
