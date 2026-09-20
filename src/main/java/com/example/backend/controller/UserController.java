package com.example.backend.controller;

import com.example.backend.dto.UserResponse; // UserResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.entity.User; // User (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.exception.ResourceNotFoundException; // ResourceNotFoundException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.repository.UserRepository; // UserRepository (repository truy vấn/lưu dữ liệu qua JPA).
import org.springframework.security.core.annotation.AuthenticationPrincipal; // thành phần Spring Security cho xác thực/phân quyền (AuthenticationPrincipal).
import org.springframework.security.core.userdetails.UserDetails; // thành phần Spring Security cho xác thực/phân quyền (UserDetails).
import org.springframework.web.bind.annotation.GetMapping; // annotation Spring MVC để khai báo route/đọc request (GetMapping).
import org.springframework.web.bind.annotation.RequestMapping; // annotation Spring MVC để khai báo route/đọc request (RequestMapping).
import org.springframework.web.bind.annotation.RestController; // annotation Spring MVC để khai báo route/đọc request (RestController).

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
