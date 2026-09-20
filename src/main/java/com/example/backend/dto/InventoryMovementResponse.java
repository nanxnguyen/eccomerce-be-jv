package com.example.backend.dto;

import com.example.backend.entity.InventoryMovement;
import com.example.backend.entity.InventoryMovementType;
import java.time.Instant;

public record InventoryMovementResponse(
        Long id,
        Long variantId,
        String sku,
        Long orderId,
        InventoryMovementType type,
        Integer stockDelta,
        Integer reservedDelta,
        Instant createdAt) {
    public static InventoryMovementResponse from(InventoryMovement movement) {
        return new InventoryMovementResponse(movement.getId(), movement.getVariantId(), movement.getSku(),
                movement.getOrderId(), movement.getType(), movement.getStockDelta(),
                movement.getReservedDelta(), movement.getCreatedAt());
    }
}
