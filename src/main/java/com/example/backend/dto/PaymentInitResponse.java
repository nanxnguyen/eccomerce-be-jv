package com.example.backend.dto;

import com.example.backend.service.payment.PaymentInitResult; // PaymentInitResult (payment).

public record PaymentInitResponse(String redirectUrl, String clientSecret) {
    // Chuyển kết quả khởi tạo từ gateway thành dữ liệu JSON trả về cho client.
    public static PaymentInitResponse from(PaymentInitResult result) {
        // Mỗi cổng thanh toán có thể dùng URL chuyển hướng hoặc client secret.
        return new PaymentInitResponse(result.redirectUrl(), result.clientSecret());
    }
}
