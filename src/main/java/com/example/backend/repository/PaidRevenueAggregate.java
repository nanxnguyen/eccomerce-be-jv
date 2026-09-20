package com.example.backend.repository;

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.

public interface PaidRevenueAggregate {
    BigDecimal getRevenue();
    Long getOrderCount();
}
