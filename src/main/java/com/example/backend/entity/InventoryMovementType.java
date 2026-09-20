package com.example.backend.entity;

public enum InventoryMovementType {
    OPENING_STOCK, // Ghi tồn ban đầu khi tạo biến thể mới.
    OPENING_BALANCE, // Lưu số dư hiện tại khi bật sổ kho lần đầu.
    SALE, // Bán hàng làm giảm tồn thực tế.
    RESERVATION, // Giữ hàng cho đơn đang chờ thanh toán.
    PAYMENT_CAPTURED, // Thanh toán xong: giảm tồn và bỏ giữ hàng.
    RESERVATION_RELEASED, // Hủy hoặc hết hạn: bỏ giữ hàng.
    RESTOCKED // Nhập trả hàng vào kho.
}
