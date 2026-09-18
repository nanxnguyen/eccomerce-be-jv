package com.example.backend.repository;

import com.example.backend.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByCartId(Long cartId);
    Optional<CartItem> findByCartIdAndVariantId(Long cartId, Long variantId);
    Optional<CartItem> findByIdAndCartId(Long id, Long cartId);
    List<CartItem> findByIdInAndCartId(List<Long> ids, Long cartId);
}
