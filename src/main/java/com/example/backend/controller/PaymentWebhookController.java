package com.example.backend.controller;

import com.example.backend.entity.PaymentMethod;
import com.example.backend.service.OrderService;
import com.example.backend.service.PaymentService;
import com.example.backend.service.payment.PaymentWebhookResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
