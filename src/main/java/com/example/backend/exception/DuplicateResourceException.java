package com.example.backend.exception;

// Ngoại lệ được ném khi cố gắng tạo tài nguyên trùng lặp (ví dụ: slug đã tồn tại).
// GlobalExceptionHandler sẽ bắt lỗi này và trả về mã 409 Conflict.
public class DuplicateResourceException extends RuntimeException {
    public DuplicateResourceException(String message) {
        super(message);
    }
}
