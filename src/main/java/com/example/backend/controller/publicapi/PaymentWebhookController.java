package com.example.backend.controller.publicapi;

import com.example.backend.entity.PaymentMethod; // PaymentMethod (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.service.OrderService; // OrderService (service xử lý nghiệp vụ).
import com.example.backend.service.PaymentService; // PaymentService (service xử lý nghiệp vụ).
import com.example.backend.service.payment.PaymentWebhookResult; // PaymentWebhookResult (payment).
import jakarta.servlet.http.HttpServletRequest; // thông tin request/response HTTP của Servlet (HttpServletRequest).
import org.springframework.http.ResponseEntity; // kiểu HTTP như status, header hoặc response body (ResponseEntity).
import org.springframework.web.bind.annotation.PostMapping; // annotation Spring MVC để khai báo route/đọc request (PostMapping).
import org.springframework.web.bind.annotation.RequestBody; // annotation Spring MVC để khai báo route/đọc request (RequestBody).
import org.springframework.web.bind.annotation.RequestMapping; // annotation Spring MVC để khai báo route/đọc request (RequestMapping).
import org.springframework.web.bind.annotation.RestController; // annotation Spring MVC để khai báo route/đọc request (RestController).

// Public theo SecurityConfig - KHÔNG qua JWT. Xác thực request đến từ đúng gateway (VNPay/Stripe)
// nằm ở tầng chữ ký (PaymentService.parseWebhook -> PaymentGateway.parseWebhook), không phải ở
// tầng Spring Security.
@RestController
@RequestMapping("/api/payments/webhooks")
public class PaymentWebhookController {

    private final PaymentService paymentService;
    private final OrderService orderService;

    public PaymentWebhookController(PaymentService paymentService, OrderService orderService) {
        this.paymentService = paymentService;
        this.orderService = orderService;
    }

    @PostMapping("/vnpay")
    public ResponseEntity<String> vnpayWebhook(HttpServletRequest request) {
        return handle(PaymentMethod.VNPAY, request, null);
    }

    @PostMapping("/stripe")
    public ResponseEntity<String> stripeWebhook(HttpServletRequest request, @RequestBody String rawBody) {
        return handle(PaymentMethod.STRIPE, request, rawBody);
    }

    private ResponseEntity<String> handle(PaymentMethod method, HttpServletRequest request, String rawBody) {
        PaymentWebhookResult result = paymentService.parseWebhook(method, request, rawBody);
        if (result == null) {
            return ResponseEntity.badRequest().body("invalid signature");
        }
        orderService.confirmPayment(result.orderId(), result.success(), result.gatewayTransactionRef());
        return ResponseEntity.ok("OK");
    }
}
