package com.example.backend.controller;

import com.example.backend.dto.CartItemAddRequest; // CartItemAddRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.CartItemQuantityRequest; // CartItemQuantityRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.CartResponse; // CartResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.service.CartService; // CartService (service xử lý nghiệp vụ).
import jakarta.validation.Valid; // annotation/API kiểm tra dữ liệu đầu vào (Valid).
import org.springframework.security.core.annotation.AuthenticationPrincipal; // thành phần Spring Security cho xác thực/phân quyền (AuthenticationPrincipal).
import org.springframework.security.core.userdetails.UserDetails; // thành phần Spring Security cho xác thực/phân quyền (UserDetails).
import org.springframework.web.bind.annotation.DeleteMapping; // annotation Spring MVC để khai báo route/đọc request (DeleteMapping).
import org.springframework.web.bind.annotation.GetMapping; // annotation Spring MVC để khai báo route/đọc request (GetMapping).
import org.springframework.web.bind.annotation.PathVariable; // annotation Spring MVC để khai báo route/đọc request (PathVariable).
import org.springframework.web.bind.annotation.PostMapping; // annotation Spring MVC để khai báo route/đọc request (PostMapping).
import org.springframework.web.bind.annotation.PutMapping; // annotation Spring MVC để khai báo route/đọc request (PutMapping).
import org.springframework.web.bind.annotation.RequestBody; // annotation Spring MVC để khai báo route/đọc request (RequestBody).
import org.springframework.web.bind.annotation.RequestMapping; // annotation Spring MVC để khai báo route/đọc request (RequestMapping).
import org.springframework.web.bind.annotation.RestController; // annotation Spring MVC để khai báo route/đọc request (RestController).

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
