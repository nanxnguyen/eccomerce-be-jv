package com.example.backend.dto;

import com.example.backend.entity.Payment;

import java.time.Instant;

public record PaymentResponse(String gateway, String status, String gatewayTransactionRef, Instant paidAt) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getGateway().name(), payment.getStatus().name(),
                payment.getGatewayTransactionRef(), payment.getPaidAt());
    }
}
