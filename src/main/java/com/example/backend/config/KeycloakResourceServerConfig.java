package com.example.backend.config;

import com.example.backend.security.KeycloakBearerTokenResolver; // KeycloakBearerTokenResolver (thành phần xác thực/phân quyền).
import org.springframework.beans.factory.annotation.Value; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Value).
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (ConditionalOnProperty).
import org.springframework.context.annotation.Bean; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Bean).
import org.springframework.context.annotation.Configuration; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Configuration).
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator; // thành phần Spring Security cho xác thực/phân quyền (DelegatingOAuth2TokenValidator).
import org.springframework.security.oauth2.core.OAuth2Error; // thành phần Spring Security cho xác thực/phân quyền (OAuth2Error).
import org.springframework.security.oauth2.core.OAuth2TokenValidator; // thành phần Spring Security cho xác thực/phân quyền (OAuth2TokenValidator).
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult; // thành phần Spring Security cho xác thực/phân quyền (OAuth2TokenValidatorResult).
import org.springframework.security.oauth2.jwt.Jwt; // thành phần Spring Security cho xác thực/phân quyền (Jwt).
import org.springframework.security.oauth2.jwt.JwtDecoder; // thành phần Spring Security cho xác thực/phân quyền (JwtDecoder).
import org.springframework.security.oauth2.jwt.JwtValidators; // thành phần Spring Security cho xác thực/phân quyền (JwtValidators).
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder; // thành phần Spring Security cho xác thực/phân quyền (NimbusJwtDecoder).
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver; // thành phần Spring Security cho xác thực/phân quyền (BearerTokenResolver).
import tools.jackson.databind.ObjectMapper; // thư viện/kiểu ObjectMapper được dùng trong file này.

@Configuration
@ConditionalOnProperty(prefix = "app.keycloak", name = "enabled", havingValue = "true")
public class KeycloakResourceServerConfig {

    @Bean
    JwtDecoder keycloakJwtDecoder(@Value("${app.keycloak.issuer-uri}") String issuer,
                                  @Value("${app.keycloak.jwk-set-uri}") String jwkSetUri,
                                  @Value("${app.keycloak.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid token audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
        return decoder;
    }

    @Bean
    BearerTokenResolver keycloakBearerTokenResolver(ObjectMapper objectMapper,
                                                     @Value("${app.keycloak.issuer-uri}") String issuer) {
        return new KeycloakBearerTokenResolver(objectMapper, issuer);
    }
}
