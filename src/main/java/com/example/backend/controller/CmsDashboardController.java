package com.example.backend.controller;

import com.example.backend.dto.CmsDashboardSummaryResponse; // CmsDashboardSummaryResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.dto.CmsRevenueTimelineResponse; // CmsRevenueTimelineResponse (DTO chuyển dữ liệu giữa HTTP và ứng dụng).
import com.example.backend.service.CmsDashboardService; // CmsDashboardService (service xử lý nghiệp vụ).
import org.springframework.security.access.prepost.PreAuthorize; // thành phần Spring Security cho xác thực/phân quyền (PreAuthorize).
import org.springframework.web.bind.annotation.GetMapping; // annotation Spring MVC để khai báo route/đọc request (GetMapping).
import org.springframework.web.bind.annotation.RequestMapping; // annotation Spring MVC để khai báo route/đọc request (RequestMapping).
import org.springframework.web.bind.annotation.RequestParam; // annotation Spring MVC để khai báo route/đọc request (RequestParam).
import org.springframework.web.bind.annotation.RestController; // annotation Spring MVC để khai báo route/đọc request (RestController).

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).

@RestController
@RequestMapping("/api/cms/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class CmsDashboardController {

    private final CmsDashboardService dashboardService;

    public CmsDashboardController(CmsDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public CmsDashboardSummaryResponse summary(@RequestParam(required = false) Instant from,
                                               @RequestParam(required = false) Instant to) {
        return dashboardService.summary(from, to);
    }

    @GetMapping("/revenue")
    public CmsRevenueTimelineResponse revenue(@RequestParam(required = false) Instant from,
                                             @RequestParam(required = false) Instant to,
                                             @RequestParam(defaultValue = "day") String granularity) {
        return dashboardService.revenue(from, to, granularity);
    }
}
