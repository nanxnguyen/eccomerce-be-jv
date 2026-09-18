package com.example.backend.dto;

import com.example.backend.service.payment.PaymentInitResult;

public record PaymentInitResponse(String redirectUrl, String clientSecret) {
    public static PaymentInitResponse from(PaymentInitResult result) {
        return new PaymentInitResponse(result.redirectUrl(), result.clientSecret());
    }
}
