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

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.

@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    // Khóa chính của một dòng hàng trong đơn.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // order_id nối chi tiết này về đơn cha.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // Giữ để tra cứu variant hiện tại, KHÔNG authoritative cho giá/tên - dùng productName/sku/
    // unitPrice snapshot bên dưới. Variant sau đổi giá hay bị xoá không ảnh hưởng order cũ. Xoá
    // 1 variant đã từng nằm trong order_items sẽ bị FK chặn (DataIntegrityViolationException -> 409,
    // xem GlobalExceptionHandler) - hành vi này tự nhiên có sẵn, không cần code thêm.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(nullable = false)
    private String productName; // Tên snapshot để đổi tên sản phẩm không sửa lịch sử mua.

    @Column(nullable = false)
    private String sku; // SKU snapshot tại lúc đặt hàng.

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice; // Đơn giá snapshot, database giữ 2 chữ số thập phân.

    @Column(nullable = false)
    private Integer quantity; // Số lượng mua của dòng này.
}
