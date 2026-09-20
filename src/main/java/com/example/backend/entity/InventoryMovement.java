package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "inventory_movements")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryMovement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long variantId; // Lưu ID dạng snapshot để giữ lịch sử kể cả khi xóa biến thể.

    @Column(nullable = false, updatable = false)
    private String sku; // Giữ SKU tại thời điểm phát sinh biến động.

    @Column(updatable = false)
    private Long orderId; // Nối biến động với đơn hàng nếu có.

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, updatable = false)
    private InventoryMovementType type; // Cho biết nguyên nhân thay đổi tồn kho.

    @Column(nullable = false, updatable = false)
    private Integer stockDelta; // Số thay đổi của tồn thực tế; âm là xuất, dương là nhập.

    @Column(nullable = false, updatable = false)
    private Integer reservedDelta; // Số thay đổi của hàng đang giữ; âm là nhả giữ.

    @Column(nullable = false, updatable = false)
    private Instant createdAt; // Thời điểm ghi nhận biến động.
}
