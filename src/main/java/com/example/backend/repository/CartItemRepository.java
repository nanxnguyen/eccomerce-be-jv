package com.example.backend.repository;

import com.example.backend.entity.CartItem; // CartItem (entity ánh xạ dữ liệu với bảng database).
import org.springframework.data.jpa.repository.JpaRepository; // kiểu Spring Data hỗ trợ truy cập/phân trang database (JpaRepository).

import java.util.List; // danh sách phần tử cùng kiểu.
import java.util.Optional; // biểu diễn kết quả có thể không tồn tại.

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    // Các phương thức này đọc các dòng hàng thuộc một giỏ.
    List<CartItem> findByCartId(Long cartId);
    Optional<CartItem> findByCartIdAndVariantId(Long cartId, Long variantId);
    Optional<CartItem> findByIdAndCartId(Long id, Long cartId);
    // Checkout chỉ lấy item có ID được chọn VÀ thuộc đúng cart hiện tại.
    List<CartItem> findByIdInAndCartId(List<Long> ids, Long cartId);
}
