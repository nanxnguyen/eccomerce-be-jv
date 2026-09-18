package com.example.backend.repository;

import com.example.backend.entity.Order;
import com.example.backend.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByIdAndUserId(Long id, Long userId);
    Page<Order> findByUserId(Long userId, Pageable pageable);
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
    List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, Instant time);
}
