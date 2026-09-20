package com.example.backend.dto;

import jakarta.validation.constraints.Email; // annotation/API kiểm tra dữ liệu đầu vào (Email).
import jakarta.validation.constraints.NotBlank; // annotation/API kiểm tra dữ liệu đầu vào (NotBlank).

/**
 * DTO chứa dữ liệu client gửi lên khi đăng nhập.
 * - @Email: xác minh email format, vì người dùng đăng nhập bằng email
 * - @NotBlank: bắt buộc password không được để trống
 */
public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
