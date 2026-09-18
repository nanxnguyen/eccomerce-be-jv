package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // slug là định danh duy nhất của danh mục, được dùng trong URL-friendly paths (ví dụ: /category/fashion)
    // Unique constraint đảm bảo không có hai danh mục nào chia sẻ cùng slug
    @Column(nullable = false, unique = true)
    private String slug;

    // Hiện tại danh mục là phẳng (flat), không có parent category. Mô hình phân cấp có thể thêm vào sau nếu cần
    @Column(columnDefinition = "TEXT")
    private String description;
}
