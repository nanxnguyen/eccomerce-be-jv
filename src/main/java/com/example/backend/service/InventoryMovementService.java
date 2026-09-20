package com.example.backend.service;

import com.example.backend.dto.InventoryMovementResponse;
import com.example.backend.entity.InventoryMovement;
import com.example.backend.entity.InventoryMovementType;
import com.example.backend.entity.ProductVariant;
import com.example.backend.exception.InvalidRequestException;
import com.example.backend.repository.InventoryMovementRepository;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryMovementService {
    private static final int MAX_PAGE_SIZE = 100;
    private final InventoryMovementRepository movements;

    public InventoryMovementService(InventoryMovementRepository movements) {
        this.movements = movements;
    }

    @Transactional
    public void record(ProductVariant variant, Long orderId, InventoryMovementType type,
                       int stockDelta, int reservedDelta) {
        // Không ghi dòng rỗng; các delta được lưu cùng transaction với cập nhật số dư.
        if (stockDelta == 0 && reservedDelta == 0) return;
        movements.save(InventoryMovement.builder()
                .variantId(variant.getId())
                .sku(variant.getSku())
                .orderId(orderId)
                .type(type)
                .stockDelta(stockDelta)
                .reservedDelta(reservedDelta)
                .createdAt(Instant.now())
                .build());
    }

    @Transactional(readOnly = true)
    public Page<InventoryMovementResponse> list(Long variantId, Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page size cannot exceed 100");
        }
        Pageable stablePage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return movements.findByVariantId(variantId, stablePage).map(InventoryMovementResponse::from);
    }
}
