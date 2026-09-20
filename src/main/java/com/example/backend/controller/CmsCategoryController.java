package com.example.backend.controller;

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

@RestController
@RequestMapping("/api/cms/categories")
@PreAuthorize("hasRole('ADMIN')")
public class CmsCategoryController {

    private final CategoryService categoryService;

    public CmsCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public List<CategoryResponse> list() {
        return categoryService.findAll();
    }

    @GetMapping("/{slug}")
    public CategoryResponse get(@PathVariable String slug) {
        return categoryService.findBySlug(slug);
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request));
    }

    @PutMapping("/{id}")
    public CategoryResponse update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
