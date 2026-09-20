package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank; // annotation/API kiểm tra dữ liệu đầu vào (NotBlank).

// DTO yêu cầu tạo/cập nhật hình ảnh sản phẩm (URL, là ảnh chính, thứ tự)
public record ProductImageRequest(@NotBlank String url, boolean isPrimary, Integer sortOrder) {}
