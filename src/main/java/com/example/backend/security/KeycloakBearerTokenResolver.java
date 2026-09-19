package com.example.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class KeycloakBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver bearerTokenResolver = new DefaultBearerTokenResolver();
    private final ObjectMapper objectMapper;
    private final String issuer;

    public KeycloakBearerTokenResolver(ObjectMapper objectMapper, String issuer) {
        this.objectMapper = objectMapper;
        this.issuer = issuer;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String token = bearerTokenResolver.resolve(request);
        if (token == null) return null;
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return issuer.equals(objectMapper.readTree(payload).path("iss").asString()) ? token : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
