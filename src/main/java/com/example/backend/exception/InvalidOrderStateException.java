package com.example.backend.exception;

// Ném khi thao tác trên Order không hợp lệ với status hiện tại (huỷ đơn đã SHIPPED, nhảy cóc
// trạng thái CONFIRMED -> DELIVERED bỏ qua SHIPPED, v.v.). GlobalExceptionHandler trả về 409 Conflict.
public class InvalidOrderStateException extends RuntimeException {
    public InvalidOrderStateException(String message) {
        super(message);
    }
}
