package com.example.backend.service.payment;

import com.example.backend.entity.Order; // Order (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentMethod; // PaymentMethod (entity ánh xạ dữ liệu với bảng database).
import jakarta.servlet.http.HttpServletRequest; // thông tin request/response HTTP của Servlet (HttpServletRequest).

public interface PaymentGateway {
    // Adapter báo nó đại diện cho phương thức nào (COD, VNPAY hoặc STRIPE).
    PaymentMethod getMethod();

    // Tạo phiên thanh toán; COD không cần redirect, gateway online có thể trả URL/secret.
    PaymentInitResult initiate(Order order);

    // Trả về null nếu chữ ký/signature không hợp lệ - PaymentService/controller coi đó là 400,
    // KHÔNG động tới order nào cả. Xem từng implementation (VnPayGateway, StripeGateway) để biết
    // cách verify chữ ký cụ thể của mỗi gateway.
    PaymentWebhookResult parseWebhook(HttpServletRequest request, String rawBody);
}
