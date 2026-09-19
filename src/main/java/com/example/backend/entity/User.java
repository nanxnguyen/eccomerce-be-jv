package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

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
