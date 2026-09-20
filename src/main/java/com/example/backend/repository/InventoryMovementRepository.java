package com.example.backend.repository;

import com.example.backend.entity.InventoryMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {
    Page<InventoryMovement> findByVariantId(Long variantId, Pageable pageable); // Lấy sổ kho của một biến thể theo trang.
}
