package com.example.backend.repository;

import com.example.backend.entity.OrderStatus; // OrderStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentMethod; // PaymentMethod (entity ánh xạ dữ liệu với bảng database).
import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.
import java.time.LocalDate; // kiểu/thao tác thời gian chuẩn Java (LocalDate).

public interface SalesReportAggregate {
    LocalDate getBucketDate();
    PaymentMethod getPaymentMethod();
    OrderStatus getOrderStatus();
    BigDecimal getPaidRevenue();
    long getPaidOrderCount();
}
