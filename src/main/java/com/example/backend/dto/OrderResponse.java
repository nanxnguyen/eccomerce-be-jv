package com.example.backend.dto;

import com.example.backend.entity.Order;
import com.example.backend.service.payment.PaymentInitResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String status,
        String paymentMethod,
        List<OrderItemResponse> items,
        String recipientName,
        String phone,
        String addressLine,
        String ward,
        String district,
        String province,
        BigDecimal totalAmount,
        Instant expiresAt,
        Instant createdAt,
        PaymentInitResponse paymentInit
) {
    // paymentInit chỉ có giá trị ngay sau checkout (redirectUrl/clientSecret) - mọi lần đọc lại
    // Order sau đó (GET list/detail) truyền initResult = null, client dùng field status để biết
    // trạng thái thanh toán hiện tại thay vì đọc lại paymentInit.
    public static OrderResponse from(Order order, PaymentInitResult initResult) {
        return new OrderResponse(
                order.getId(),
                order.getStatus().name(),
                order.getPaymentMethod().name(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getRecipientName(),
                order.getPhone(),
                order.getAddressLine(),
                order.getWard(),
                order.getDistrict(),
                order.getProvince(),
                order.getTotalAmount(),
                order.getExpiresAt(),
                order.getCreatedAt(),
                initResult == null ? null : PaymentInitResponse.from(initResult)
        );
    }
}
