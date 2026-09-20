package com.example.backend.dto;

import com.example.backend.entity.OrderStatus; // OrderStatus (entity ánh xạ dữ liệu với bảng database).
import jakarta.validation.constraints.NotNull; // annotation/API kiểm tra dữ liệu đầu vào (NotNull).

// Body dùng khi CMS yêu cầu đổi trạng thái đơn; trạng thái phải có.
public record OrderStatusUpdateRequest(
        @NotNull OrderStatus status // Spring trả 400 nếu client không gửi trạng thái.
) {}
