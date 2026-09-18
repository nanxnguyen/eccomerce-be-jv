package com.example.backend.exception;

// Ngoại lệ được ném khi tài nguyên (Product, Category, v.v.) không tìm thấy.
// GlobalExceptionHandler sẽ bắt lỗi này và trả về mã 404 Not Found.
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
