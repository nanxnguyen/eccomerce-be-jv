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

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).

// price/stockQuantity sống ở đây, không phải ở Product: mỗi biến thể (size, màu...) của
// cùng 1 sản phẩm có giá và tồn kho riêng biệt.
@Entity
@Table(name = "product_variants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductVariant {

    // Khóa chính của một biến thể size/màu/SKU trong product_variants.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Owning side của quan hệ với Product (giữ cột khóa ngoại product_id).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, unique = true)
    private String sku;

    private String size;

    private String color;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    // Số lượng vật lý hiện có trong kho.
    @Column(nullable = false)
    private Integer stockQuantity;

    // Số lượng đang bị "giữ chỗ" cho các Order status=PENDING_PAYMENT (VNPay/Stripe) - CHƯA trừ
    // khỏi stockQuantity thật. Chỉ trừ thật (stockQuantity -= qty) khi webhook xác nhận thanh toán
    // thành công (OrderService.confirmPayment, Task 18) hoặc ngay lúc tạo Order với COD (không có
    // bước chờ gateway). Xem spec §6 Checkout & Payment Flow.
    @Column(nullable = false)
    @Builder.Default
    private Integer reservedQuantity = 0;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    // Số có thể bán = tồn vật lý trừ lượng đã giữ cho các đơn chờ thanh toán.
    public int getAvailableQuantity() {
        return stockQuantity - reservedQuantity;
    }
}
