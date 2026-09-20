package com.example.backend.dto;

import com.example.backend.entity.ProductStatus; // ProductStatus (entity ánh xạ dữ liệu với bảng database).
import jakarta.validation.constraints.NotBlank; // annotation/API kiểm tra dữ liệu đầu vào (NotBlank).
import jakarta.validation.constraints.NotNull; // annotation/API kiểm tra dữ liệu đầu vào (NotNull).

// DTO yêu cầu tạo/cập nhật sản phẩm (danh mục, tên, slug, mô tả, trạng thái hiển thị).
// status để trống (null) có ý nghĩa khác nhau tùy thao tác - xem ProductService:
// tạo mới -> mặc định ACTIVE; sửa -> giữ nguyên status hiện tại của sản phẩm, không reset về ACTIVE.
public record ProductRequest(
        @NotNull Long categoryId,
        @NotBlank String name,
        @NotBlank String slug,
        String description,
        ProductStatus status
) {}
