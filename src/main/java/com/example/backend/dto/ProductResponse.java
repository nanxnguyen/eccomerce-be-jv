package com.example.backend.dto;

import com.example.backend.entity.Product; // Product (entity ánh xạ dữ liệu với bảng database).

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.util.List; // danh sách phần tử cùng kiểu.

// DTO phản hồi chi tiết sản phẩm với danh sách biến thể và hình ảnh.
// Gồm variants/images để trang chi tiết sản phẩm có đầy đủ dữ liệu trong một API call.
// Status là String (tên enum) chứ không phải ProductStatus entity để tách rời DTO khỏi Java type của entity.
public record ProductResponse(
        Long id,
        String name,
        String slug,
        String description,
        String status,
        CategoryResponse category,
        List<ProductVariantResponse> variants,
        List<ProductImageResponse> images,
        Instant createdAt,
        Instant updatedAt
) {
    // Chuyển đổi từ entity Product sang DTO response với các nested lists
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getSlug(),
                product.getDescription(),
                product.getStatus().name(),
                CategoryResponse.from(product.getCategory()),
                product.getVariants().stream().map(ProductVariantResponse::from).toList(),
                product.getImages().stream().map(ProductImageResponse::from).toList(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
