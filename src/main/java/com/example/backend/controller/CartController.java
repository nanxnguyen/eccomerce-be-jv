package com.example.backend.controller;

import com.example.backend.dto.CartItemAddRequest;
import com.example.backend.dto.CartItemQuantityRequest;
import com.example.backend.dto.CartResponse;
import com.example.backend.service.CartService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartResponse getCart(@AuthenticationPrincipal UserDetails userDetails) {
        return cartService.getCart(userDetails.getUsername());
    }

    @PostMapping("/items")
    public CartResponse addItem(@AuthenticationPrincipal UserDetails userDetails,
                                 @Valid @RequestBody CartItemAddRequest request) {
        return cartService.addItem(userDetails.getUsername(), request);
    }

    @PutMapping("/items/{itemId}")
    public CartResponse updateItem(@AuthenticationPrincipal UserDetails userDetails,
                                    @PathVariable Long itemId, @Valid @RequestBody CartItemQuantityRequest request) {
        return cartService.updateItem(userDetails.getUsername(), itemId, request);
    }

    @DeleteMapping("/items/{itemId}")
    public CartResponse removeItem(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long itemId) {
        return cartService.removeItem(userDetails.getUsername(), itemId);
    }
}
