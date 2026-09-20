package com.example.backend.controller.cms;

import com.example.backend.dto.OrderResponse; // OrderResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.OrderStatusUpdateRequest; // OrderStatusUpdateRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.entity.OrderStatus; // OrderStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentMethod; // PaymentMethod (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.entity.PaymentStatus; // PaymentStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.service.OrderService; // OrderService (service xử lý nghiệp vụ).
import com.example.backend.service.PdfDocumentService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.thymeleaf.context.Context;

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).

@RestController
@RequestMapping("/api/cms/orders")
@PreAuthorize("hasRole('ADMIN')")
public class CmsOrderController {

    private final OrderService orderService;
    private final PdfDocumentService pdfDocuments;

    public CmsOrderController(OrderService orderService, PdfDocumentService pdfDocuments) {
        this.orderService = orderService;
        this.pdfDocuments = pdfDocuments;
    }

    @GetMapping
    public Page<OrderResponse> list(@RequestParam(required = false) OrderStatus status,
                                    @RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to,
                                    @RequestParam(required = false) PaymentStatus paymentStatus,
                                    @RequestParam(required = false) PaymentMethod paymentMethod,
                                    @RequestParam(required = false) String buyerEmail,
                                    @PageableDefault(size = 20) Pageable pageable) {
        return orderService.listCmsOrders(status, from, to, paymentStatus, paymentMethod, buyerEmail, pageable);
    }

    @PutMapping("/{id}/status")
    public OrderResponse advanceStatus(@PathVariable Long id,
                                       @Valid @RequestBody OrderStatusUpdateRequest request) {
        return orderService.advanceStatus(id, request.status());
    }

    @GetMapping(value = "/{id}/packing-slip.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> packingSlip(@PathVariable Long id) {
        Context context = new Context(java.util.Locale.forLanguageTag("vi"));
        context.setVariable("title", "Phiếu đóng gói");
        context.setVariable("invoice", false);
        OrderResponse order = orderService.getCmsOrder(id);
        context.setVariable("order", order);
        context.setVariable("createdAt", java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(order.createdAt()));
        byte[] pdf = pdfDocuments.render("pdf/order", context);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"phieu-dong-goi-" + id + ".pdf\"")
                .body(pdf);
    }
}
