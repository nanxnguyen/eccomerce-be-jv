package com.example.backend.security;

import com.example.backend.entity.User;
import com.example.backend.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Cầu nối giữa Spring Security và dữ liệu User của ứng dụng.
 * Spring Security không biết gì về entity `User` hay bảng `users` - nó chỉ biết làm việc
 * với interface `UserDetails` (username, password, authorities). UserDetailsService là nơi
 * "dịch" từ User (entity của mình) sang UserDetails (thứ Spring Security hiểu).
 * JwtAuthFilter sẽ gọi loadUserByUsername(email) để lấy thông tin user + quyền hạn (authorities)
 * dựa vào email lấy được từ token, rồi đặt vào SecurityContext.
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user with email: " + email));

        // Spring Security yêu cầu prefix "ROLE_" cho authority để hasRole("ADMIN")/@PreAuthorize
        // hoạt động đúng - nó tự thêm "ROLE_" khi so khớp, nên ở đây phải khai báo sẵn.
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                .build();
    }
}
