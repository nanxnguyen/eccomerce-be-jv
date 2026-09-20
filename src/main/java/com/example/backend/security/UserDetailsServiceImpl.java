package com.example.backend.security;

import com.example.backend.entity.User; // User (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.repository.UserRepository; // UserRepository (repository truy vấn/lưu dữ liệu qua JPA).
import org.springframework.security.core.authority.SimpleGrantedAuthority; // thành phần Spring Security cho xác thực/phân quyền (SimpleGrantedAuthority).
import org.springframework.security.core.userdetails.UserDetails; // thành phần Spring Security cho xác thực/phân quyền (UserDetails).
import org.springframework.security.core.userdetails.UserDetailsService; // thành phần Spring Security cho xác thực/phân quyền (UserDetailsService).
import org.springframework.security.core.userdetails.UsernameNotFoundException; // thành phần Spring Security cho xác thực/phân quyền (UsernameNotFoundException).
import org.springframework.stereotype.Service; // thành phần Spring phục vụ dependency injection/cấu hình ứng dụng (Service).

import java.util.List; // danh sách phần tử cùng kiểu.

/**
 * Cầu nối giữa Spring Security và dữ liệu User của ứng dụng.
 * Spring Security không biết gì về entity `User` hay bảng `users` - nó chỉ biết làm việc
 * với interface `UserDetails` (username, password, authorities). UserDetailsService là nơi
 * "dịch" từ User (entity của mình) sang UserDetails (thứ Spring Security hiểu).
 * JwtAuthFilter sẽ gọi loadUserById(userId) để lấy thông tin user + quyền hạn (authorities)
 * dựa vào userId lấy được từ token (sub), rồi đặt vào SecurityContext.
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
        return toUserDetails(user);
    }

    // Dùng bởi JwtAuthFilter: access token mang userId (sub), không mang email nữa.
    public UserDetails loadUserById(Long id) throws UsernameNotFoundException {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("No user with id: " + id));
        return toUserDetails(user);
    }

    // username của UserDetails vẫn là email (không phải userId) - mọi controller/service khác
    // (Cart/Order/Address/User) đều lấy user hiện tại qua userDetails.getUsername() = email, đổi
    // ở đây sẽ vỡ toàn bộ các chỗ đó. Chỉ cách TRA CỨU user (theo id thay vì theo email) là mới.
    private UserDetails toUserDetails(User user) {
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                .build();
    }
}
