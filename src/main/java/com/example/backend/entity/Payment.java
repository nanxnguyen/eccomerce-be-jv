package com.example.backend.entity;

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).

import org.hibernate.annotations.CreationTimestamp; // tính năng Hibernate/JPA cho database (CreationTimestamp).
import org.hibernate.annotations.UpdateTimestamp; // tính năng Hibernate/JPA cho database (UpdateTimestamp).

import jakarta.persistence.Column; // annotation/API JPA để ánh xạ entity với database (Column).
import jakarta.persistence.Entity; // annotation/API JPA để ánh xạ entity với database (Entity).
import jakarta.persistence.EnumType; // annotation/API JPA để ánh xạ entity với database (EnumType).
import jakarta.persistence.Enumerated; // annotation/API JPA để ánh xạ entity với database (Enumerated).
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

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    // Khóa chính của bản ghi payments.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Owning side (giữ cột order_id) - Order.payment là mappedBy, xem Order.assignPayment().
    @OneToOne
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod gateway; // COD, VNPAY hoặc STRIPE; được lưu dưới dạng tên enum.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status; // SUCCESS cho COD ngay; PENDING khi chờ cổng online.

    // null cho COD.
    private String gatewayTransactionRef; // Mã giao dịch ngoài, có thể null trước khi gateway trả về.

    private Instant paidAt; // Thời điểm thanh toán thành công; null khi chưa trả tiền.

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
