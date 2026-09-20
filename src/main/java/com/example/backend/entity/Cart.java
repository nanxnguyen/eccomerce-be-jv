package com.example.backend.entity;

import jakarta.persistence.Entity; // annotation/API JPA để ánh xạ entity với database (Entity).
import jakarta.persistence.FetchType; // annotation/API JPA để ánh xạ entity với database (FetchType).
import jakarta.persistence.GeneratedValue; // annotation/API JPA để ánh xạ entity với database (GeneratedValue).
import jakarta.persistence.GenerationType; // annotation/API JPA để ánh xạ entity với database (GenerationType).
import jakarta.persistence.Id; // annotation/API JPA để ánh xạ entity với database (Id).
import jakarta.persistence.JoinColumn; // annotation/API JPA để ánh xạ entity với database (JoinColumn).
import jakarta.persistence.OneToOne; // annotation/API JPA để ánh xạ entity với database (OneToOne).
import jakarta.persistence.Table; // annotation/API JPA để ánh xạ entity với database (Table).
import lombok.AllArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (AllArgsConstructor).
import lombok.Builder; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Builder).
import lombok.Getter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Getter).
import lombok.NoArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (NoArgsConstructor).
import lombok.Setter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Setter).

// Cart không có price snapshot / timestamp: đây là trạng thái tạm, luôn phản ánh giá/tồn kho live
// của variant (khác Order - đã snapshot mọi thứ tại thời điểm mua). Xem spec §4.
@Entity
@Table(name = "carts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cart {

    // Khóa chính bảng carts; PostgreSQL cấp ID khi INSERT.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Mỗi giỏ thuộc một user; user_id là khóa ngoại và phải duy nhất.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;
}
