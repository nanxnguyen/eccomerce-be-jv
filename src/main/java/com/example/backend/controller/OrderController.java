package com.example.backend.controller;

import com.example.backend.dto.CheckoutRequest;
import com.example.backend.dto.OrderResponse;
import com.example.backend.dto.PaymentResponse;
import com.example.backend.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public Page<OrderResponse> list(@AuthenticationPrincipal UserDetails userDetails,
                                     @PageableDefault(size = 20) Pageable pageable) {
        return orderService.getOrders(userDetails.getUsername(), pageable);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        return orderService.getOrder(userDetails.getUsername(), id);
    }

    @GetMapping("/{id}/payment")
    public PaymentResponse getPayment(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        return orderService.getPayment(userDetails.getUsername(), id);
    }

    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(@AuthenticationPrincipal UserDetails userDetails,
                                                    @Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.checkout(userDetails.getUsername(), request));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        return orderService.cancel(userDetails.getUsername(), id);
    }
}
