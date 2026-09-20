package com.example.backend.repository;

import com.example.backend.entity.NotificationOutbox; // NotificationOutbox (entity ánh xạ dữ liệu với bảng database).
import org.springframework.data.jpa.repository.JpaRepository; // kiểu Spring Data hỗ trợ truy cập/phân trang database (JpaRepository).

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {
  boolean existsByEventKey(String eventKey);
}
