package com.example.backend.repository;

import com.example.backend.entity.Cart; // Cart (entity ánh xạ dữ liệu với bảng database).
import org.springframework.data.jpa.repository.JpaRepository; // kiểu Spring Data hỗ trợ truy cập/phân trang database (JpaRepository).

import java.util.Optional; // biểu diễn kết quả có thể không tồn tại.

public interface CartRepository extends JpaRepository<Cart, Long> {
    // Tìm giỏ theo khóa ngoại user_id; Spring Data tự tạo SELECT từ tên hàm.
    Optional<Cart> findByUserId(Long userId);
}
