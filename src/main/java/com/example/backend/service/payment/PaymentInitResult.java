package com.example.backend.service.payment;

public record PaymentInitResult(String redirectUrl, String clientSecret) {
        public static PaymentInitResult none() {
        return new PaymentInitResult(null, null); // Không có URL hoặc secret cần gửi cho client.
    }

        public static PaymentInitResult redirect(String url) {
        return new PaymentInitResult(url, null); // Chỉ trả URL chuyển hướng đến cổng thanh toán.
    }

        public static PaymentInitResult clientSecret(String secret) {
        return new PaymentInitResult(null, secret); // Chỉ trả secret để client xác nhận giao dịch.
    }
}
