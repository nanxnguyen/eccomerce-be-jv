package com.example.backend.repository;

public interface LowStockVariantProjection {
    Long getProductId();
    String getProductName();
    Long getVariantId();
    String getSku();
    Integer getStockQuantity();
    Integer getReservedQuantity();
    Integer getAvailableQuantity();
}
