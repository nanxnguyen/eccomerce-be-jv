package com.example.backend.dto;

// Một dòng báo cáo tồn kho CMS; available = stock - reserved.
public record CmsInventoryItem(
        Long productId, // ID sản phẩm chứa biến thể.
        String productName, // Tên để nhân viên nhận diện sản phẩm.
        Long variantId, // ID biến thể cụ thể.
        String sku, // Mã SKU của biến thể.
        Integer stockQuantity, // Số lượng vật lý đang có.
        Integer reservedQuantity, // Số lượng đang giữ cho đơn chờ thanh toán.
        Integer availableQuantity, // Số lượng còn có thể bán.
        Integer threshold // Ngưỡng dùng để xác định tồn kho thấp.
) {}
