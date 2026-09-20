package com.example.backend.controller.publicapi;

import com.example.backend.dto.CategoryRequest; // CategoryRequest (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.CategoryResponse; // CategoryResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.service.CategoryService; // CategoryService (service xử lý nghiệp vụ).
import jakarta.validation.Valid; // annotation/API kiểm tra dữ liệu đầu vào (Valid).
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
import org.springframework.web.bind.annotation.RestController; // annotation Spring MVC để khai báo route/đọc request (RestController).

import java.util.List; // danh sách phần tử cùng kiểu.

/**
 * GET là public (SecurityConfig đã permitAll cho GET /api/categories/**), còn tạo/sửa/xóa
 * chỉ dành cho ADMIN.
 *
 * @PreAuthorize("hasRole('ADMIN')") hoạt động nhờ @EnableMethodSecurity ở SecurityConfig (Task 6):
 * Spring bọc method này bằng một proxy, trước khi method thực thi sẽ kiểm tra authorities của
 * Authentication hiện tại trong SecurityContext. hasRole('ADMIN') tự thêm prefix "ROLE_" rồi so
 * khớp với authority "ROLE_ADMIN" mà UserDetailsServiceImpl (Task 6) đã gán cho user có role ADMIN.
 * Nếu không khớp (có token nhưng không đủ quyền) -> ném AccessDeniedException -> GlobalExceptionHandler
 * trả 403. Nếu không có token/token không hợp lệ, request không tới được đây - bị chặn từ tầng
 * filter/entry point (Task 7) và trả 401 trước khi method này được gọi.
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public List<CategoryResponse> findAll() {
        return categoryService.findAll();
    }

    @GetMapping("/{slug}")
    public CategoryResponse findBySlug(@PathVariable String slug) {
        return categoryService.findBySlug(slug);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
