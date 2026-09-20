package com.example.backend.entity;

import jakarta.persistence.CascadeType; // annotation/API JPA để ánh xạ entity với database (CascadeType).
import jakarta.persistence.Column; // annotation/API JPA để ánh xạ entity với database (Column).
import jakarta.persistence.Entity; // annotation/API JPA để ánh xạ entity với database (Entity).
import jakarta.persistence.EnumType; // annotation/API JPA để ánh xạ entity với database (EnumType).
import jakarta.persistence.Enumerated; // annotation/API JPA để ánh xạ entity với database (Enumerated).
import jakarta.persistence.FetchType; // annotation/API JPA để ánh xạ entity với database (FetchType).
import jakarta.persistence.GeneratedValue; // annotation/API JPA để ánh xạ entity với database (GeneratedValue).
import jakarta.persistence.GenerationType; // annotation/API JPA để ánh xạ entity với database (GenerationType).
import jakarta.persistence.Id; // annotation/API JPA để ánh xạ entity với database (Id).
import jakarta.persistence.JoinColumn; // annotation/API JPA để ánh xạ entity với database (JoinColumn).
import jakarta.persistence.ManyToOne; // annotation/API JPA để ánh xạ entity với database (ManyToOne).
import jakarta.persistence.OneToMany; // annotation/API JPA để ánh xạ entity với database (OneToMany).
import jakarta.persistence.OneToOne; // annotation/API JPA để ánh xạ entity với database (OneToOne).
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
import java.util.ArrayList; // danh sách có thể thêm phần tử.
import java.util.List; // danh sách phần tử cùng kiểu.

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    // Khóa chính; database cấp số khi Hibernate INSERT đơn mới.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // user_id là khóa ngoại chỉ khách đã đặt đơn.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Lưu enum theo tên chữ (ví dụ CONFIRMED), không theo số thứ tự.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    // Phương thức thanh toán cũng được lưu theo tên enum.
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
    // Cascade lưu/xóa các order_items cùng Order; mappedBy cho biết OrderItem giữ cột order_id.
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    // Quan hệ 1-1; Payment giữ cột khóa ngoại order_id.
    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private Payment payment;

    // Cùng lý do addVariant()/addImage() trên Product: owning side của quan hệ (order_id) nằm ở
    // OrderItem/Payment - chỉ add vào list/field trong bộ nhớ KHÔNG đủ, phải set cả chiều ngược lại
    // để Hibernate lưu đúng khoá ngoại.
    public void addItem(OrderItem item) {
        // Thêm dòng hàng vào danh sách mà response có thể đọc ngay.
        items.add(item);
        // Gán phía giữ khóa ngoại để Hibernate biết order_id cần lưu.
        item.setOrder(this);
    }

    public void assignPayment(Payment payment) {
        // Gắn payment vào Order trong bộ nhớ.
        this.payment = payment;
        // Gán phía Payment giữ cột order_id trong database.
        payment.setOrder(this);
    }
}
