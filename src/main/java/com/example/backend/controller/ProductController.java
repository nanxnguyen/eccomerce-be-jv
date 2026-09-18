package com.example.backend.controller;

import com.example.backend.dto.ProductImageRequest;
import com.example.backend.dto.ProductRequest;
import com.example.backend.dto.ProductResponse;
import com.example.backend.dto.ProductSummaryResponse;
import com.example.backend.dto.ProductVariantRequest;
import com.example.backend.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// GET là public (SecurityConfig đã permitAll cho GET /api/products/**), còn tạo/sửa/xóa/thêm
// variant/image chỉ dành cho ADMIN - xem giải thích cơ chế @PreAuthorize ở CategoryController.
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public Page<ProductSummaryResponse> list(@RequestParam(required = false) Long categoryId,
                                              @RequestParam(required = false) String search,
                                              @PageableDefault(size = 20) Pageable pageable) {
        return productService.list(categoryId, search, pageable);
    }

    @GetMapping("/{slug}")
    public ProductResponse getBySlug(@PathVariable String slug) {
        return productService.getBySlug(slug);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // {id} là Long: truyền 1 giá trị không phải số (vd: slug) khiến Spring không convert được
    // path variable -> ném MethodArgumentTypeMismatchException. GlobalExceptionHandler có handler
    // riêng cho ngoại lệ này (xem GlobalExceptionHandler.handleTypeMismatch), trả về 400 Bad
    // Request rõ ràng chứ không rơi vào handler Exception.class chung nữa.
    @PostMapping("/{id}/variants")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> addVariant(@PathVariable Long id, @Valid @RequestBody ProductVariantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.addVariant(id, request));
    }

    @PostMapping("/{id}/images")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> addImage(@PathVariable Long id, @Valid @RequestBody ProductImageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.addImage(id, request));
    }
}
