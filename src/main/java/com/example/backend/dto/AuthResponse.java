package com.example.backend.dto;

/**
 * DTO chứa JWT token mà server trả về sau khi đăng nhập hoặc đăng ký thành công.
 * Client lưu token này và gửi kèm trong mỗi request cần xác thực (trong Authorization header).
 */
public record AuthResponse(String token) {}
