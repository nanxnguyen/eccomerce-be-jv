package com.example.backend.controller;

import com.example.backend.dto.NotificationResponse; // NotificationResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.exception.InvalidRequestException; // InvalidRequestException (loại lỗi nghiệp vụ hoặc dữ liệu).
import com.example.backend.service.NotificationService; // NotificationService (service xử lý nghiệp vụ).
import org.springframework.data.domain.Page; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Page).
import org.springframework.data.domain.PageRequest; // kiểu Spring Data hỗ trợ truy cập/phân trang database (PageRequest).
import org.springframework.data.domain.Pageable; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Pageable).
import org.springframework.data.domain.Sort; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Sort).
import org.springframework.security.core.annotation.AuthenticationPrincipal; // thành phần Spring Security cho xác thực/phân quyền (AuthenticationPrincipal).
import org.springframework.security.core.userdetails.UserDetails; // thành phần Spring Security cho xác thực/phân quyền (UserDetails).
import org.springframework.web.bind.annotation.*; // annotation Spring MVC để khai báo route/đọc request (*).

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
  private final NotificationService service;

  public NotificationController(NotificationService service) {
    this.service = service;
  }

  @GetMapping
  public Page<NotificationResponse> list(
      @AuthenticationPrincipal UserDetails user,
      @RequestParam(required = false) Boolean read,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(user.getUsername(), read, false, page(page, size));
  }

  @GetMapping("/unread-count")
  public long unreadCount(@AuthenticationPrincipal UserDetails user) {
    return service.unreadCount(user.getUsername(), false);
  }

  @PutMapping("/{id}/read")
  public NotificationResponse markRead(
      @AuthenticationPrincipal UserDetails user, @PathVariable Long id) {
    return service.markRead(user.getUsername(), id, false);
  }

  @PutMapping("/read-all")
  public int markAllRead(@AuthenticationPrincipal UserDetails user) {
    return service.markAllRead(user.getUsername(), false);
  }

  private static Pageable page(int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new InvalidRequestException(
          "page must be nonnegative and size must be between 1 and 100");
    return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
  }
}
