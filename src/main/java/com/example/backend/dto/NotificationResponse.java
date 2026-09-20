package com.example.backend.dto;

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).

public record NotificationResponse(
    Long id, // ID thông báo để client đánh dấu đã đọc.
    Long orderId, // Đơn liên quan; có thể null nếu thông báo không gắn với đơn.
    String type, // Mã loại thông báo để giao diện chọn cách hiển thị.
    String title, // Tiêu đề ngắn.
    String message, // Nội dung đầy đủ.
    boolean read, // Đã đọc hay chưa.
    Instant createdAt) {} // Thời điểm tạo thông báo.
