package com.example.backend.repository;

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.

public interface ProductSalesAggregate {
    String getProductName();
    String getSku();
    long getUnitsSold();
    BigDecimal getRevenue();
}
