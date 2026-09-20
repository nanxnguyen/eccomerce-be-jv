package com.example.backend.dto;

import jakarta.validation.constraints.Email; // annotation/API kiểm tra dữ liệu đầu vào (Email).
import jakarta.validation.constraints.NotBlank; // annotation/API kiểm tra dữ liệu đầu vào (NotBlank).
import jakarta.validation.constraints.Size; // annotation/API kiểm tra dữ liệu đầu vào (Size).

/**
 * DTO chứa dữ liệu client gửi lên khi đăng ký tài khoản mới.
 * - @NotBlank: bắt buộc name không được để trống
 * - @Email: xác minh email có format đúng
 * - @Size(min = 6): password tối thiểu 6 ký tự, tăng bảo mật cơ bản
 * - phone: không bắt buộc (nullable), vì có thể đăng ký mà chưa có SĐT
 */
public record RegisterRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 6) String password,
        String phone
) {}
