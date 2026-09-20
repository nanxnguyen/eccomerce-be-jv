package com.example.backend.repository;

import com.example.backend.entity.LowStockAlertState; // LowStockAlertState (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.LowStockAlertStateId; // LowStockAlertStateId (entity ánh xạ dữ liệu với bảng database).
import org.springframework.data.jpa.repository.JpaRepository; // kiểu Spring Data hỗ trợ truy cập/phân trang database (JpaRepository).

public interface LowStockAlertStateRepository
    extends JpaRepository<LowStockAlertState, LowStockAlertStateId> {}
