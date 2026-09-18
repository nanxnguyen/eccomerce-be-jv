package com.example.backend.repository;

import com.example.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository interface cho entity User.
 * Spring Data JPA sẽ tự động sinh code SQL dựa vào tên method:
 * - findByEmail(String email): phân tích tên → sinh SQL: SELECT * FROM users WHERE email = ?
 * - existsByEmail(String email): sinh SQL: SELECT COUNT(*) FROM users WHERE email = ?
 * Không cần viết @Query hoặc SQL thủ công - Spring Data làm hết.
 */
public interface UserRepository extends JpaRepository<User, Long> {
    // Tìm user theo email, trả về Optional (có thể không tìm thấy)
    Optional<User> findByEmail(String email);

    // Kiểm tra email có tồn tại không (trả về true/false)
    boolean existsByEmail(String email);
}
