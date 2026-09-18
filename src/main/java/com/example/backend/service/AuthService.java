package com.example.backend.service;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.exception.DuplicateResourceException;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        // Kiểm tra trùng email TRƯỚC khi hash mật khẩu/lưu DB - tránh tốn công hash (BCrypt cố tình
        // chậm để chống brute-force) cho một request chắc chắn sẽ bị từ chối.
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already registered: " + request.email());
        }

        // Role luôn là CUSTOMER, không đọc role từ request: đây là API đăng ký công khai (ai cũng
        // gọi được), nếu cho phép client tự chọn role thì ai cũng có thể tự phong mình làm ADMIN.
        // Muốn có tài khoản ADMIN thì phải tạo bằng cách khác (seed dữ liệu, hoặc endpoint riêng
        // chỉ ADMIN hiện có mới gọi được).
        User user = User.builder()
                .name(request.name())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .phone(request.phone())
                .role(Role.CUSTOMER)
                .build();

        userRepository.save(user);

        String token = jwtService.generateToken(user.getEmail(), user.getRole().name());
        return new AuthResponse(token);
    }

    public AuthResponse login(LoginRequest request) {
        // Cố tình dùng CÙNG MỘT thông báo lỗi cho cả 2 trường hợp "không tìm thấy email" và
        // "sai mật khẩu". Nếu thông báo khác nhau, kẻ tấn công có thể dò ra được email nào đã
        // tồn tại trong hệ thống (user enumeration attack) chỉ bằng cách thử đăng nhập.
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        // So khớp mật khẩu bằng passwordEncoder.matches(), KHÔNG bao giờ so sánh chuỗi (==, .equals())
        // vì passwordHash trong DB là chuỗi đã hash (BCrypt), không phải mật khẩu gốc - so sánh
        // chuỗi trực tiếp sẽ luôn sai. matches() tự hash lại mật khẩu vừa nhập bằng cùng salt rồi so sánh.
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(user.getEmail(), user.getRole().name());
        return new AuthResponse(token);
    }
}
