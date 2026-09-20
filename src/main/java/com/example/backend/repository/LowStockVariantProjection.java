package com.example.backend.repository;

public interface LowStockVariantProjection {
    Long getProductId(); // ID sản phẩm chứa biến thể.
    String getProductName(); // Tên sản phẩm để hiển thị trong danh sách tồn kho.
    Long getVariantId(); // ID biến thể được kiểm tra tồn kho.
    String getSku(); // Mã SKU của biến thể.
    Integer getStockQuantity(); // Số lượng hàng đang có trong kho.
    Integer getReservedQuantity(); // Số lượng đã giữ cho đơn chờ thanh toán.
    Integer getAvailableQuantity(); // Số lượng còn có thể bán.
}
