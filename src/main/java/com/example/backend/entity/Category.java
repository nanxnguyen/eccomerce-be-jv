package com.example.backend.entity;

import jakarta.persistence.Column; // annotation/API JPA để ánh xạ entity với database (Column).
import jakarta.persistence.Entity; // annotation/API JPA để ánh xạ entity với database (Entity).
import jakarta.persistence.GeneratedValue; // annotation/API JPA để ánh xạ entity với database (GeneratedValue).
import jakarta.persistence.GenerationType; // annotation/API JPA để ánh xạ entity với database (GenerationType).
import jakarta.persistence.Id; // annotation/API JPA để ánh xạ entity với database (Id).
import jakarta.persistence.Table; // annotation/API JPA để ánh xạ entity với database (Table).
import jakarta.persistence.ManyToOne; // annotation/API JPA để ánh xạ entity với database (ManyToOne).
import jakarta.persistence.OneToMany; // annotation/API JPA để ánh xạ entity với database (OneToMany).
import jakarta.persistence.FetchType; // annotation/API JPA để ánh xạ entity với database (FetchType).
import jakarta.persistence.JoinColumn; // annotation/API JPA để ánh xạ entity với database (JoinColumn).
import lombok.AllArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (AllArgsConstructor).
import lombok.Builder; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Builder).
import lombok.Getter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Getter).
import lombok.NoArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (NoArgsConstructor).
import lombok.Setter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Setter).
import java.util.ArrayList; // danh sách có thể thêm phần tử.
import java.util.List; // danh sách phần tử cùng kiểu.

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

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @OneToMany(mappedBy = "parent")
    @Builder.Default
    private List<Category> children = new ArrayList<>();
}
