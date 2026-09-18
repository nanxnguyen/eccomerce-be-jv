package com.example.backend.exception;

// Ném khi 1 IP vượt ngưỡng số lần login sai trong 1 khoảng thời gian - xem LoginRateLimiter.
public class TooManyAttemptsException extends RuntimeException {
    public TooManyAttemptsException(String message) {
        super(message);
    }
}
