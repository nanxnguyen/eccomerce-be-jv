package com.example.backend.dto;

import com.example.backend.entity.Category; // Category (entity ánh xạ dữ liệu với bảng database).

import java.util.List; // danh sách phần tử cùng kiểu.

public record CategoryResponse(Long id, String name, String slug, String description,
                               Long parentId, List<CategoryResponse> children) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug(), category.getDescription(),
                category.getParent() == null ? null : category.getParent().getId(), List.of());
    }
}
