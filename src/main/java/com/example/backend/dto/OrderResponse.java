package com.example.backend.dto;

import com.example.backend.entity.Order; // Order (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.service.payment.PaymentInitResult; // PaymentInitResult (payment).

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.util.List; // danh sách phần tử cùng kiểu.

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
                // Lấy ID, trạng thái và phương thức từ Order; enum đổi sang chữ để tạo JSON.
                order.getId(),
                order.getStatus().name(),
                order.getPaymentMethod().name(),
                // Chuyển từng entity OrderItem thành DTO response.
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                // Địa chỉ snapshot đã lưu trên Order, không đọc lại Address hiện tại.
                order.getRecipientName(),
                order.getPhone(),
                order.getAddressLine(),
                order.getWard(),
                order.getDistrict(),
                order.getProvince(),
                // Tổng tiền và thời hạn thanh toán đã được tính trong OrderService.checkout.
                order.getTotalAmount(),
                order.getExpiresAt(),
                order.getCreatedAt(),
                // Chỉ có paymentInit ngay sau checkout online; COD/đọc lại đơn sẽ để null.
                initResult == null ? null : PaymentInitResponse.from(initResult)
        );
    }
}
