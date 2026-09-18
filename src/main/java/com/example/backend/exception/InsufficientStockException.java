package com.example.backend.exception;

// Ném khi checkout không đủ hàng (available = stock_quantity - reserved_quantity < số lượng đặt).
// GlobalExceptionHandler bắt lỗi này và trả về 409 Conflict.
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
