package com.example.backend.dto;

import com.example.backend.entity.Role;
import com.example.backend.entity.User;

/**
 * DTO chứa thông tin user mà server trả về khi client request lấy thông tin tài khoản.
 * Chỉ trả về dữ liệu cần thiết (id, name, email, phone, role), không trả về passwordHash để bảo mật.
 * Static method from() giúp dễ dàng chuyển đổi từ User entity sang DTO.
 */
public record UserResponse(Long id, String name, String email, String phone, Role role) {
    /**
     * Chuyển đổi User entity sang UserResponse DTO.
     * Ánh xạ từng trường từ user entity sang các trường tương ứng trong DTO.
     */
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getPhone(), user.getRole());
    }
}
