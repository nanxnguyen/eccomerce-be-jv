package com.example.backend.controller.cms;

import com.example.backend.dto.CmsInventoryItem; // CmsInventoryItem (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.InventoryMovementResponse;
import com.example.backend.service.CmsInventoryService; // CmsInventoryService (service xử lý nghiệp vụ).
import com.example.backend.service.InventoryMovementService;
import org.springframework.data.domain.Page; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Page).
import org.springframework.data.domain.Pageable; // kiểu Spring Data hỗ trợ truy cập/phân trang database (Pageable).
import org.springframework.data.web.PageableDefault; // kiểu Spring Data hỗ trợ truy cập/phân trang database (PageableDefault).
import org.springframework.security.access.prepost.PreAuthorize; // thành phần Spring Security cho xác thực/phân quyền (PreAuthorize).
import org.springframework.web.bind.annotation.GetMapping; // annotation Spring MVC để khai báo route/đọc request (GetMapping).
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping; // annotation Spring MVC để khai báo route/đọc request (RequestMapping).
import org.springframework.web.bind.annotation.RequestParam; // annotation Spring MVC để khai báo route/đọc request (RequestParam).
import org.springframework.web.bind.annotation.RestController; // annotation Spring MVC để khai báo route/đọc request (RestController).

@RestController
@RequestMapping("/api/cms/inventory")
@PreAuthorize("hasRole('ADMIN')")
public class CmsInventoryController {

    private final CmsInventoryService inventoryService;
    private final InventoryMovementService movementService;

    public CmsInventoryController(CmsInventoryService inventoryService, InventoryMovementService movementService) {
        this.inventoryService = inventoryService;
        this.movementService = movementService;
    }

    @GetMapping("/low-stock")
    public Page<CmsInventoryItem> lowStock(@RequestParam(required = false) Integer threshold,
                                           @RequestParam(required = false) Long categoryId,
                                           @PageableDefault(size = 20) Pageable pageable) {
        return inventoryService.lowStock(threshold, categoryId, pageable);
    }

    @GetMapping("/variants/{variantId}/movements")
    // Chỉ admin được xem lịch sử thay đổi tồn và lượng giữ của biến thể.
    public Page<InventoryMovementResponse> movements(@PathVariable Long variantId,
                                                      @PageableDefault(size = 20) Pageable pageable) {
        return movementService.list(variantId, pageable);
    }
}
