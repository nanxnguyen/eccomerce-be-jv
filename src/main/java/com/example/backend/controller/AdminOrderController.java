package com.example.backend.controller;

import com.example.backend.dto.OrderResponse;
import com.example.backend.dto.OrderStatusUpdateRequest;
import com.example.backend.entity.OrderStatus;
import com.example.backend.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Page<OrderResponse> list(@RequestParam(required = false) OrderStatus status,
                                     @PageableDefault(size = 20) Pageable pageable) {
        return orderService.listAllOrders(status, pageable);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponse advanceStatus(@PathVariable Long id, @Valid @RequestBody OrderStatusUpdateRequest request) {
        return orderService.advanceStatus(id, request.status());
    }
}
