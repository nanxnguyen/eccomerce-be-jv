package com.example.backend.security;

import com.example.backend.service.KeycloakUserProvisioner; // KeycloakUserProvisioner (service xử lý nghiệp vụ).
import org.springframework.core.convert.converter.Converter; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Converter).
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken; // thành phần Spring Security cho xác thực/phân quyền (UsernamePasswordAuthenticationToken).
import org.springframework.security.core.GrantedAuthority; // thành phần Spring Security cho xác thực/phân quyền (GrantedAuthority).
import org.springframework.security.core.authority.SimpleGrantedAuthority; // thành phần Spring Security cho xác thực/phân quyền (SimpleGrantedAuthority).
import org.springframework.security.oauth2.jwt.Jwt; // thành phần Spring Security cho xác thực/phân quyền (Jwt).
import org.springframework.stereotype.Component; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Component).
import org.springframework.util.StringUtils; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (StringUtils).

import java.util.Collection; // tiện ích collection chuẩn Java (Collection).
import java.util.List; // danh sách phần tử cùng kiểu.
import java.util.Map; // bản đồ khóa–giá trị.

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
