package com.example.backend.repository;

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.
import java.time.LocalDate; // kiểu/thao tác thời gian chuẩn Java (LocalDate).

public interface DailyRevenueAggregate {
    LocalDate getPaidDate();
    BigDecimal getRevenue();
    Long getOrderCount();
}
