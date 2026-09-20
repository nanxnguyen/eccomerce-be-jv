package com.example.backend.controller;

import com.example.backend.dto.CheckoutRequest; // CheckoutRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.OrderResponse; // OrderResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.PaymentResponse; // PaymentResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.service.OrderService; // OrderService (service xử lý nghiệp vụ).
import jakarta.validation.Valid; // annotation/API kiểm tra dữ liệu đầu vào (Valid).
import org.springframework.data.domain.Page; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Page).
import org.springframework.data.domain.Pageable; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Pageable).
import org.springframework.data.web.PageableDefault; // kiểu Spring Data hỗ trợ truy cập/phân trang database (PageableDefault).
import org.springframework.http.HttpStatus; // kiểu HTTP như status, header hoặc response body (HttpStatus).
import org.springframework.http.ResponseEntity; // kiểu HTTP như status, header hoặc response body (ResponseEntity).
import org.springframework.security.core.annotation.AuthenticationPrincipal; // thành phần Spring Security cho xác thực/phân quyền (AuthenticationPrincipal).
import org.springframework.security.core.userdetails.UserDetails; // thành phần Spring Security cho xác thực/phân quyền (UserDetails).
import org.springframework.web.bind.annotation.GetMapping; // annotation Spring MVC để khai báo route/đọc request (GetMapping).
import org.springframework.web.bind.annotation.PathVariable; // annotation Spring MVC để khai báo route/đọc request (PathVariable).
import org.springframework.web.bind.annotation.PostMapping; // annotation Spring MVC để khai báo route/đọc request (PostMapping).
import org.springframework.web.bind.annotation.RequestBody; // annotation Spring MVC để khai báo route/đọc request (RequestBody).
import org.springframework.web.bind.annotation.RequestMapping; // annotation Spring MVC để khai báo route/đọc request (RequestMapping).
import org.springframework.web.bind.annotation.RestController; // annotation Spring MVC để khai báo route/đọc request (RestController).

@RestController
@RequestMapping("/api/orders")
// Cửa vào HTTP cho luồng đơn hàng; controller nhận request rồi giao nghiệp vụ cho OrderService.
public class OrderController {

    private final OrderService orderService;

    // Spring truyền service vào qua constructor (dependency injection).
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // GET /api/orders: lấy đơn thuộc user đang đăng nhập; mặc định mỗi trang có 20 đơn.
    @GetMapping
    public Page<OrderResponse> list(@AuthenticationPrincipal UserDetails userDetails,
                                     @PageableDefault(size = 20) Pageable pageable) {
        // UserDetails đã được security dựng từ token; username ở ứng dụng này là email.
        return orderService.getOrders(userDetails.getUsername(), pageable);
    }

    // GET /api/orders/{id}: service kiểm tra đơn có thuộc user hiện tại không.
    @GetMapping("/{id}")
    public OrderResponse get(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        // Spring chuyển {id} từ đoạn URL thành Long trước khi gọi hàm.
        return orderService.getOrder(userDetails.getUsername(), id);
    }

    // GET /api/orders/{id}/payment: lấy thông tin thanh toán của đơn thuộc user.
    @GetMapping("/{id}/payment")
    public PaymentResponse getPayment(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        // Controller không truy vấn DB trực tiếp; service xử lý quyền sở hữu và dữ liệu.
        return orderService.getPayment(userDetails.getUsername(), id);
    }

    // POST /api/orders/checkout: body JSON được chuyển thành CheckoutRequest.
    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(@AuthenticationPrincipal UserDetails userDetails,
                                                    @Valid @RequestBody CheckoutRequest request) {
        // @AuthenticationPrincipal lấy user đã xác thực; @Valid kiểm tra DTO trước khi vào service.
        // Service tạo đơn; HTTP 201 cho biết tài nguyên Order mới đã được tạo.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.checkout(userDetails.getUsername(), request));
    }

    // POST /api/orders/{id}/cancel: service quyết định đơn có thể hủy theo trạng thái hay không.
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        return orderService.cancel(userDetails.getUsername(), id);
    }
}
