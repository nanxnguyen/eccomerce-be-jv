package com.example.backend.entity;

import jakarta.persistence.Column; // annotation/API JPA để ánh xạ entity với database (Column).
import jakarta.persistence.Entity; // annotation/API JPA để ánh xạ entity với database (Entity).
import jakarta.persistence.EnumType; // annotation/API JPA để ánh xạ entity với database (EnumType).
import jakarta.persistence.Enumerated; // annotation/API JPA để ánh xạ entity với database (Enumerated).
import jakarta.persistence.GeneratedValue; // annotation/API JPA để ánh xạ entity với database (GeneratedValue).
import jakarta.persistence.GenerationType; // annotation/API JPA để ánh xạ entity với database (GenerationType).
import jakarta.persistence.Id; // annotation/API JPA để ánh xạ entity với database (Id).
import jakarta.persistence.Table; // annotation/API JPA để ánh xạ entity với database (Table).
import lombok.AllArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (AllArgsConstructor).
import lombok.Builder; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Builder).
import lombok.Getter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Getter).
import lombok.NoArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (NoArgsConstructor).
import lombok.Setter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Setter).
import org.hibernate.annotations.CreationTimestamp; // tính năng Hibernate/JPA cho database (CreationTimestamp).
import org.hibernate.annotations.UpdateTimestamp; // tính năng Hibernate/JPA cho database (UpdateTimestamp).

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).

/**
 * Lớp đại diện cho bảng 'users' trong cơ sở dữ liệu.
 * @Entity: báo cho JPA biết đây là một entity (được ánh xạ với bảng DB)
 * Lombok (@Getter, @Setter, @Builder): tự động sinh getter/setter/builder thay vì viết thủ công
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    // ID tự động tăng, khóa chính của bảng
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    // Lưu trữ mật khẩu dưới dạng hash (BCrypt), không bao giờ lưu mật khẩu plain text
    @Column
    private String passwordHash;

    @Column(name = "keycloak_subject", unique = true, length = 255)
    private String keycloakSubject;

    private String phone;

    /**
     * Lưu vai trò dưới dạng STRING ("CUSTOMER" hoặc "ADMIN") thay vì dạng ordinal (0, 1).
     * STRING an toàn hơn: nếu sắp xếp lại thứ tự enum, dữ liệu cũ vẫn đúng;
     * ordinal phụ thuộc vào vị trí trong enum, dễ bị vỡ nếu thêm/xóa role giữa chừng.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // Tự động ghi lại timestamp khi tạo record, không thể cập nhật sau đó
    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    // Tự động cập nhật timestamp mỗi lần record được sửa
    @UpdateTimestamp
    private Instant updatedAt;
}
