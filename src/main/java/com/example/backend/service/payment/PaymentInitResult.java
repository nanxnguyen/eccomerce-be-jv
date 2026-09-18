package com.example.backend.service.payment;

public record PaymentInitResult(String redirectUrl, String clientSecret) {
    public static PaymentInitResult none() {
        return new PaymentInitResult(null, null);
    }

    public static PaymentInitResult redirect(String url) {
        return new PaymentInitResult(url, null);
    }

    public static PaymentInitResult clientSecret(String secret) {
        return new PaymentInitResult(null, secret);
    }
}
