package com.example.backend.service.payment;

import com.example.backend.entity.Order;
import com.example.backend.entity.PaymentMethod;
import jakarta.servlet.http.HttpServletRequest;

public interface PaymentGateway {
    PaymentMethod getMethod();

    PaymentInitResult initiate(Order order);

    // Trả về null nếu chữ ký/signature không hợp lệ - PaymentService/controller coi đó là 400,
    // KHÔNG động tới order nào cả. Xem từng implementation (VnPayGateway, StripeGateway) để biết
    // cách verify chữ ký cụ thể của mỗi gateway.
    PaymentWebhookResult parseWebhook(HttpServletRequest request, String rawBody);
}
