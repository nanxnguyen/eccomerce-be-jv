package com.example.backend.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
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
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;

    // Snapshot copy từ Address lúc checkout - KHÔNG FK vào addresses, sửa/xoá address sau không
    // ảnh hưởng order cũ. Xem spec §4/§6.
    @Column(nullable = false)
    private String recipientName;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String addressLine;

    private String ward;
    private String district;
    private String province;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    // null cho COD (không có gì để expire); set = now + order.payment-expiry-minutes cho VNPay/Stripe.
    private Instant expiresAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private Payment payment;

    // Cùng lý do addVariant()/addImage() trên Product: owning side của quan hệ (order_id) nằm ở
    // OrderItem/Payment - chỉ add vào list/field trong bộ nhớ KHÔNG đủ, phải set cả chiều ngược lại
    // để Hibernate lưu đúng khoá ngoại.
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public void assignPayment(Payment payment) {
        this.payment = payment;
        payment.setOrder(this);
    }
}
