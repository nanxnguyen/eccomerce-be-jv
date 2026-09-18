package com.example.backend.service.payment;

public record PaymentWebhookResult(Long orderId, boolean success, String gatewayTransactionRef) {}
