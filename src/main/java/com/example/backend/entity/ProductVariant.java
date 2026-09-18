package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

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

    public int getAvailableQuantity() {
        return stockQuantity - reservedQuantity;
    }
}
