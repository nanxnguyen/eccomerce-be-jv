package com.example.backend.security;

import com.example.backend.service.KeycloakUserProvisioner;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
public class KeycloakJwtAuthenticationConverter
        implements Converter<Jwt, UsernamePasswordAuthenticationToken> {

    private final KeycloakUserProvisioner userProvisioner;

    public KeycloakJwtAuthenticationConverter(KeycloakUserProvisioner userProvisioner) {
        this.userProvisioner = userProvisioner;
    }

    @Override
    public UsernamePasswordAuthenticationToken convert(Jwt jwt) {
        var principal = userProvisioner.resolve(jwt);
        List<GrantedAuthority> authorities = realmRoles(jwt).stream()
                .filter(role -> role.equals("ADMIN") || role.equals("CUSTOMER"))
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .map(GrantedAuthority.class::cast)
                .toList();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    private static List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) return List.of();
        return roles.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(StringUtils::hasText).toList();
    }
}
