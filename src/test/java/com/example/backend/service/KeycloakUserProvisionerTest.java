package com.example.backend.service;

import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeycloakUserProvisionerTest {

    private final UserRepository users = mock(UserRepository.class);
    private final KeycloakUserProvisioner provisioner = new KeycloakUserProvisioner(users);

    @Test
    void createsCustomerOnlyForVerifiedKeycloakEmail() {
        when(users.findByKeycloakSubject("kc-123")).thenReturn(Optional.empty());
        when(users.findByEmail("new@example.test")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserDetails result = provisioner.resolve(jwt("kc-123", "new@example.test", true));

        assertEquals("new@example.test", result.getUsername());
        var saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertEquals("kc-123", saved.getValue().getKeycloakSubject());
        assertEquals(Role.CUSTOMER, saved.getValue().getRole());
        assertNull(saved.getValue().getPasswordHash());
    }

    @Test
    void linksVerifiedEmailToExistingLocalAccountWithoutChangingItsPasswordOrRole() {
        User existing = User.builder().id(7L).name("Local Admin").email("new@example.test")
                .passwordHash("legacy-hash").role(Role.ADMIN).build();
        when(users.findByKeycloakSubject("kc-123")).thenReturn(Optional.empty());
        when(users.findByEmail("new@example.test")).thenReturn(Optional.of(existing));
        when(users.save(existing)).thenReturn(existing);

        UserDetails result = provisioner.resolve(jwt("kc-123", "new@example.test", true));

        assertEquals("new@example.test", result.getUsername());
        assertEquals("kc-123", existing.getKeycloakSubject());
        assertEquals("legacy-hash", existing.getPasswordHash());
        assertEquals(Role.ADMIN, existing.getRole());
    }

    @Test
    void refusesToLinkAnUnverifiedEmail() {
        when(users.findByKeycloakSubject("kc-123")).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class,
                () -> provisioner.resolve(jwt("kc-123", "new@example.test", false)));
    }

    private static Jwt jwt(String subject, String email, boolean verified) {
        return Jwt.withTokenValue("signed-token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("email", email)
                .claim("email_verified", verified)
                .claim("name", "New Customer")
                .build();
    }
}
