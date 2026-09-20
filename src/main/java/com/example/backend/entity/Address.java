package com.example.backend.entity;

import jakarta.persistence.Column; // annotation/API JPA để ánh xạ entity với database (Column).
import jakarta.persistence.Entity; // annotation/API JPA để ánh xạ entity với database (Entity).
import jakarta.persistence.FetchType; // annotation/API JPA để ánh xạ entity với database (FetchType).
import jakarta.persistence.GeneratedValue; // annotation/API JPA để ánh xạ entity với database (GeneratedValue).
import jakarta.persistence.GenerationType; // annotation/API JPA để ánh xạ entity với database (GenerationType).
import jakarta.persistence.Id; // annotation/API JPA để ánh xạ entity với database (Id).
import jakarta.persistence.JoinColumn; // annotation/API JPA để ánh xạ entity với database (JoinColumn).
import jakarta.persistence.ManyToOne; // annotation/API JPA để ánh xạ entity với database (ManyToOne).
import jakarta.persistence.Table; // annotation/API JPA để ánh xạ entity với database (Table).
import lombok.AllArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (AllArgsConstructor).
import lombok.Builder; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Builder).
import lombok.Getter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Getter).
import lombok.NoArgsConstructor; // Lombok tự sinh mã Java lặp lại lúc biên dịch (NoArgsConstructor).
import lombok.Setter; // Lombok tự sinh mã Java lặp lại lúc biên dịch (Setter).
import org.hibernate.annotations.CreationTimestamp; // tính năng Hibernate/JPA cho database (CreationTimestamp).
import org.hibernate.annotations.UpdateTimestamp; // tính năng Hibernate/JPA cho database (UpdateTimestamp).

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).

@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// Entity này là bản Java của một dòng trong bảng addresses.
public class Address {

    // Cột id là khóa chính; PostgreSQL tự cấp giá trị.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // user_id nối địa chỉ với chủ tài khoản; LAZY chỉ đọc user khi thật sự cần.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String recipientName; // Tên người nhận trên địa chỉ đang lưu.

    @Column(nullable = false)
    private String phone; // Số điện thoại liên hệ.

    @Column(nullable = false)
    private String addressLine; // Địa chỉ đường/số nhà.

    private String ward; // Phường/xã, có thể null.
    private String district; // Quận/huyện, có thể null.
    private String province; // Tỉnh/thành phố, có thể null.

    // KHÔNG tự chặn "chỉ 1 default/user" bằng unique constraint ở DB: mọi dòng còn lại đều có
    // isDefault=false, trùng giá trị nhau -> DB không thể coi false là "phải duy nhất". Ràng buộc
    // này nằm ở tầng service (AddressService.setDefault, Task 5).
    @Column(nullable = false)
    private boolean isDefault;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
