package com.example.backend.dto;

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.

// Một dòng báo cáo doanh số theo tên/SKU được chụp tại thời điểm mua hàng.
public record CmsProductSalesReportRow(
    String productName, // Tên sản phẩm trong chi tiết đơn hàng.
    String sku, // SKU trong chi tiết đơn hàng.
    long unitsSold, // Tổng số lượng đã bán.
    BigDecimal revenue // Tổng tiền = đơn giá snapshot nhân số lượng.
) {}
