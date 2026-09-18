package com.example.backend.controller;

import com.example.backend.dto.UserResponse;
import com.example.backend.entity.User;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserDetails userDetails) {
        // @AuthenticationPrincipal: Spring Security annotation tự động inject UserDetails
        // của user hiện tại đã được xác thực (được thiết lập bởi JwtAuthFilter).
        // Nếu không có token hợp lệ, request sẽ bị chặn ở JwtAuthFilter, không bao giờ đến endpoint này.

        // Lấy full User entity từ repository bằng email (username trong UserDetails).
        // UserDetails chỉ chứa username và authorities, không chứa đủ thông tin (phone, name, id)
        // để trả về UserResponse đầy đủ, nên phải tìm lại entity từ DB.
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return UserResponse.from(user);
    }
}
