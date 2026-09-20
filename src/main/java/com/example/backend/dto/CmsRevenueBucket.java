package com.example.backend.dto;

import java.math.BigDecimal; // tính tiền chính xác, tránh sai số số thực.
import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).

// Một mốc dữ liệu trên biểu đồ doanh thu.
public record CmsRevenueBucket(
        Instant bucketStart, // Thời điểm bắt đầu ngày/tuần/tháng trong biểu đồ.
        BigDecimal paidRevenue, // Doanh thu các đơn đã thanh toán trong mốc này.
        long paidOrderCount // Số đơn đã thanh toán trong mốc này.
) {}
