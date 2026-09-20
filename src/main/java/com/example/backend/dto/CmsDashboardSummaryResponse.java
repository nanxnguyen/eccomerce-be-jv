package com.example.backend.dto;

import com.example.backend.entity.OrderStatus; // OrderStatus (entity ánh xạ dữ liệu với bảng database).

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.util.Map; // bản đồ khóa–giá trị.

public record CmsDashboardSummaryResponse(
        Instant from, // Đầu khoảng thời gian dashboard tổng hợp.
        Instant to, // Cuối khoảng thời gian dashboard tổng hợp.
        Instant generatedAt, // Thời điểm server tạo kết quả.
        BigDecimal paidRevenue, // Tổng doanh thu của đơn đã thanh toán.
        long paidOrderCount, // Số đơn đã thanh toán.
        BigDecimal averagePaidOrderValue, // Doanh thu chia cho số đơn đã thanh toán.
        Map<OrderStatus, Long> ordersByStatus, // Số đơn theo từng trạng thái.
        long productCount, // Tổng số sản phẩm.
        long lowStockVariantCount // Số biến thể có tồn khả dụng thấp.
) {}
