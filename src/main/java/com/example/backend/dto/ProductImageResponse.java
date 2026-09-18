package com.example.backend.dto;

import com.example.backend.entity.ProductImage;

// DTO phản hồi chi tiết hình ảnh sản phẩm sau khi lưu
public record ProductImageResponse(Long id, String url, boolean isPrimary, Integer sortOrder) {
    // Chuyển đổi từ entity ProductImage sang DTO response
    public static ProductImageResponse from(ProductImage image) {
        return new ProductImageResponse(image.getId(), image.getUrl(), image.isPrimary(), image.getSortOrder());
    }
}
