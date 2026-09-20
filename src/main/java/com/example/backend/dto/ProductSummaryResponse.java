package com.example.backend.dto;

import com.example.backend.entity.Product; // Product (entity ánh xạ dữ liệu với bảng database).

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).

// DTO rút gọn dùng cho trang danh sách sản phẩm (GET /api/products) - KHÔNG có variants/images.
// Trang danh sách không cần đủ biến thể/ảnh của từng sản phẩm, chỉ trang chi tiết (GET /{slug},
// dùng ProductResponse đầy đủ) mới cần. Tách riêng DTO này để tránh lazy-load 2 collection đó cho
// từng dòng trong trang - nếu dùng chung ProductResponse cho cả list lẫn detail, mỗi sản phẩm
// trong trang sẽ kéo theo 2 query phụ (variants + images), nhân lên N+1 lần theo số sản phẩm/trang.
public record ProductSummaryResponse(
        Long id,
        String name,
        String slug,
        String description,
        String status,
        CategoryResponse category,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductSummaryResponse from(Product product) {
        return new ProductSummaryResponse(
                product.getId(),
                product.getName(),
                product.getSlug(),
                product.getDescription(),
                product.getStatus().name(),
                CategoryResponse.from(product.getCategory()),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
