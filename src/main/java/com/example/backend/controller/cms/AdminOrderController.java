package com.example.backend.controller.cms;

import com.example.backend.dto.OrderResponse; // OrderResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.OrderStatusUpdateRequest; // OrderStatusUpdateRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.entity.OrderStatus; // OrderStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.service.OrderService; // OrderService (service xử lý nghiệp vụ).
import jakarta.validation.Valid; // annotation/API kiểm tra dữ liệu đầu vào (Valid).
import org.springframework.data.domain.Page; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Page).
import org.springframework.data.domain.Pageable; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Pageable).
import org.springframework.data.web.PageableDefault; // kiểu Spring Data hỗ trợ truy cập/phân trang database (PageableDefault).
import org.springframework.security.access.prepost.PreAuthorize; // thành phần Spring Security cho xác thực/phân quyền (PreAuthorize).
import org.springframework.web.bind.annotation.GetMapping; // annotation Spring MVC để khai báo route/đọc request (GetMapping).
import org.springframework.web.bind.annotation.PathVariable; // annotation Spring MVC để khai báo route/đọc request (PathVariable).
import org.springframework.web.bind.annotation.PutMapping; // annotation Spring MVC để khai báo route/đọc request (PutMapping).
import org.springframework.web.bind.annotation.RequestBody; // annotation Spring MVC để khai báo route/đọc request (RequestBody).
import org.springframework.web.bind.annotation.RequestMapping; // annotation Spring MVC để khai báo route/đọc request (RequestMapping).
import org.springframework.web.bind.annotation.RequestParam; // annotation Spring MVC để khai báo route/đọc request (RequestParam).
import org.springframework.web.bind.annotation.RestController; // annotation Spring MVC để khai báo route/đọc request (RestController).

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
