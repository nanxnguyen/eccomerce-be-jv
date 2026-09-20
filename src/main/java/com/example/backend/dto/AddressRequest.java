package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank; // annotation/API kiểm tra dữ liệu đầu vào (NotBlank).

// DTO request: dữ liệu địa chỉ gửi từ JSON; @NotBlank khiến Spring trả 400 nếu thiếu trường bắt buộc.
public record AddressRequest(
        @NotBlank String recipientName, // Tên người nhận hàng.
        @NotBlank String phone, // Số điện thoại liên hệ.
        @NotBlank String addressLine, // Số nhà, tên đường hoặc địa chỉ chi tiết.
        String ward, // Phường/xã; có thể chưa cung cấp.
        String district, // Quận/huyện; có thể chưa cung cấp.
        String province // Tỉnh/thành phố; có thể chưa cung cấp.
) {}
