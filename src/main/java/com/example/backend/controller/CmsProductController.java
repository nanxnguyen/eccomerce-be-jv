package com.example.backend.controller;

import com.example.backend.dto.ProductImageRequest; // ProductImageRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.ProductRequest; // ProductRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.ProductResponse; // ProductResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.ProductSummaryResponse; // ProductSummaryResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.ProductVariantRequest; // ProductVariantRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.entity.ProductStatus; // ProductStatus (entity ánh xạ dữ liệu với bảng database).
import com.example.backend.service.ProductService; // ProductService (service xử lý nghiệp vụ).
import jakarta.validation.Valid; // annotation/API kiểm tra dữ liệu đầu vào (Valid).
import org.springframework.data.domain.Page; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Page).
import org.springframework.data.domain.Pageable; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Pageable).
import org.springframework.data.web.PageableDefault; // kiểu Spring Data hỗ trợ truy cập/phân trang database (PageableDefault).
import org.springframework.http.HttpStatus; // kiểu HTTP như status, header hoặc response body (HttpStatus).
import org.springframework.http.ResponseEntity; // kiểu HTTP như status, header hoặc response body (ResponseEntity).
import org.springframework.security.access.prepost.PreAuthorize; // thành phần Spring Security cho xác thực/phân quyền (PreAuthorize).
import org.springframework.web.bind.annotation.DeleteMapping; // annotation Spring MVC để khai báo route/đọc request (DeleteMapping).
import org.springframework.web.bind.annotation.GetMapping; // annotation Spring MVC để khai báo route/đọc request (GetMapping).
import org.springframework.web.bind.annotation.PathVariable; // annotation Spring MVC để khai báo route/đọc request (PathVariable).
import org.springframework.web.bind.annotation.PostMapping; // annotation Spring MVC để khai báo route/đọc request (PostMapping).
import org.springframework.web.bind.annotation.PutMapping; // annotation Spring MVC để khai báo route/đọc request (PutMapping).
import org.springframework.web.bind.annotation.RequestBody; // annotation Spring MVC để khai báo route/đọc request (RequestBody).
import org.springframework.web.bind.annotation.RequestMapping; // annotation Spring MVC để khai báo route/đọc request (RequestMapping).
import org.springframework.web.bind.annotation.RequestParam; // annotation Spring MVC để khai báo route/đọc request (RequestParam).
import org.springframework.web.bind.annotation.RestController; // annotation Spring MVC để khai báo route/đọc request (RestController).

@RestController
@RequestMapping("/api/cms/products")
@PreAuthorize("hasRole('ADMIN')")
public class CmsProductController {

    private final ProductService productService;

    public CmsProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public Page<ProductSummaryResponse> list(@RequestParam(required = false) ProductStatus status,
                                              @RequestParam(required = false) Long categoryId,
                                              @RequestParam(required = false) String search,
                                              @PageableDefault(size = 20) Pageable pageable) {
        return productService.listAdmin(status, categoryId, search, pageable);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return productService.getByIdAdmin(id);
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/variants")
    public ResponseEntity<ProductResponse> addVariant(@PathVariable Long id,
                                                       @Valid @RequestBody ProductVariantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.addVariant(id, request));
    }

    @PostMapping("/{id}/images")
    public ResponseEntity<ProductResponse> addImage(@PathVariable Long id,
                                                    @Valid @RequestBody ProductImageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.addImage(id, request));
    }
}
