package com.example.backend.repository;

import com.example.backend.entity.User; // User (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.Role; // Role (entity ánh xạ dữ liệu với bảng database).
import org.springframework.data.jpa.repository.JpaRepository; // kiểu Spring Data hỗ trợ truy cập/phân trang database (JpaRepository).

import java.util.Optional; // biểu diễn kết quả có thể không tồn tại.

/**
 * Repository interface cho entity User.
 * Spring Data JPA sẽ tự động sinh code SQL dựa vào tên method:
 * - findByEmail(String email): phân tích tên → sinh SQL: SELECT * FROM users WHERE email = ?
 * - existsByEmail(String email): sinh SQL: SELECT COUNT(*) FROM users WHERE email = ?
 * Không cần viết @Query hoặc SQL thủ công - Spring Data làm hết.
 */
public interface UserRepository extends JpaRepository<User, Long> {
    // OrderService dùng email từ token để xác định chủ của request checkout.
    // Spring Data tạo SELECT theo tên findByEmail; Optional biểu diễn trường hợp không có kết quả.
    Optional<User> findByEmail(String email);
    Optional<User> findByKeycloakSubject(String keycloakSubject);

    // Kiểm tra email có tồn tại không (trả về true/false)
    boolean existsByEmail(String email);
    java.util.List<User> findAllByRole(Role role);
}
