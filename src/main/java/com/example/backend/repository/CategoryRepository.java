package com.example.backend.repository;

import com.example.backend.entity.Category; // Category (entity ánh xạ dữ liệu với bảng database).
import org.springframework.data.jpa.repository.JpaRepository; // kiểu Spring Data hỗ trợ truy cập/phân trang database (JpaRepository).

import java.util.Optional; // biểu diễn kết quả có thể không tồn tại.

public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findBySlug(String slug);
    boolean existsBySlug(String slug);
    boolean existsByParentId(Long parentId);
}
