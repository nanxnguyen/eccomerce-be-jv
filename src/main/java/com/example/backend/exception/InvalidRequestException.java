package com.example.backend.exception;

public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message); // Giữ thông báo để handler trả lỗi request dễ hiểu cho client.
    }
}
