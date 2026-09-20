package com.example.backend.service;

import com.example.backend.entity.Role; // Role (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.User; // User (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.repository.UserRepository; // UserRepository (repository truy vấn/lưu dữ liệu qua JPA).
import org.springframework.security.authentication.BadCredentialsException; // thành phần Spring Security cho xác thực/phân quyền (BadCredentialsException).
import org.springframework.security.core.userdetails.UserDetails; // thành phần Spring Security cho xác thực/phân quyền (UserDetails).
import org.springframework.security.oauth2.jwt.Jwt; // thành phần Spring Security cho xác thực/phân quyền (Jwt).
import org.springframework.stereotype.Service; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Service).
import org.springframework.transaction.annotation.Transactional; // quản lý transaction database (Transactional).
import org.springframework.util.StringUtils; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (StringUtils).

@Service
public class KeycloakUserProvisioner {

    private final UserRepository userRepository;

    public KeycloakUserProvisioner(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UserDetails resolve(Jwt jwt) {
        String subject = jwt.getSubject();
        if (!StringUtils.hasText(subject)) throw new BadCredentialsException("Keycloak token has no subject");

        User user = userRepository.findByKeycloakSubject(subject).orElse(null);
        if (user == null) {
            String email = jwt.getClaimAsString("email");
            if (!StringUtils.hasText(email) || !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
                throw new BadCredentialsException("A verified email is required to link this account");
            }

            user = userRepository.findByEmail(email).orElseGet(() -> User.builder()
                    .name(displayName(jwt, email))
                    .email(email)
                    .role(Role.CUSTOMER)
                    .build());
            if (user.getKeycloakSubject() != null && !user.getKeycloakSubject().equals(subject)) {
                throw new BadCredentialsException("Email is already linked to another identity");
            }
            user.setKeycloakSubject(subject);
            user = userRepository.save(user);
        }

        return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                .password(user.getPasswordHash() == null ? "" : user.getPasswordHash())
                .authorities(java.util.List.of())
                .build();
    }

    private static String displayName(Jwt jwt, String email) {
        String name = jwt.getClaimAsString("name");
        if (!StringUtils.hasText(name)) name = jwt.getClaimAsString("preferred_username");
        return StringUtils.hasText(name) ? name : email;
    }
}
