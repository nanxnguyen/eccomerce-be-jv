package com.example.backend.dto;

import com.example.backend.entity.PaymentMethod; // PaymentMethod (entity ánh xạ dữ liệu với bảng database).
import jakarta.validation.constraints.NotEmpty; // annotation/API kiểm tra dữ liệu đầu vào (NotEmpty).
import jakarta.validation.constraints.NotNull; // annotation/API kiểm tra dữ liệu đầu vào (NotNull).

import java.util.List; // danh sách phần tử cùng kiểu.

// Body của POST /api/orders/checkout; Spring validate trước khi gọi OrderController.
public record CheckoutRequest(
        @NotEmpty List<Long> cartItemIds, // Các dòng giỏ khách muốn mua; danh sách không được rỗng.
        @NotNull Long addressId, // Địa chỉ giao hàng đã lưu thuộc tài khoản hiện tại.
        @NotNull PaymentMethod paymentMethod // Cách thanh toán, ví dụ COD, VNPAY hoặc STRIPE.
) {}
