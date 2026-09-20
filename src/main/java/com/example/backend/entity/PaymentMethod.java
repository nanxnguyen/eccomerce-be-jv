package com.example.backend.entity;

public enum PaymentMethod {
    COD, // Thanh toán khi nhận hàng.
    VNPAY, // Thanh toán qua cổng VNPay.
    STRIPE // Thanh toán qua cổng Stripe.
}
