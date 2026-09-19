package com.example.backend.security;

import com.example.backend.service.KeycloakUserProvisioner;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KeycloakJwtAuthenticationConverterTest {

    @Test
    void usesLocalEmailAndMapsOnlyApplicationRealmRoles() {
        KeycloakUserProvisioner provisioner = mock(KeycloakUserProvisioner.class);
        User localUser = new User("local@example.test", "", List.of());
        Jwt jwt = Jwt.withTokenValue("signed-token")
                .header("alg", "RS256")
                .subject("keycloak-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("realm_access", Map.of("roles", List.of("ADMIN", "CUSTOMER", "offline_access")))
                .build();
        when(provisioner.resolve(jwt)).thenReturn(localUser);

        Authentication authentication = new KeycloakJwtAuthenticationConverter(provisioner).convert(jwt);

        assertEquals("local@example.test", authentication.getName());
        assertEquals(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_CUSTOMER")),
                authentication.getAuthorities().stream().sorted((a, b) -> a.getAuthority().compareTo(b.getAuthority())).toList());
    }
}
