package com.example.backend.dto;

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).
import java.util.List; // danh sách phần tử cùng kiểu.

// Response tổng của biểu đồ: khoảng thời gian, cách gom nhóm và các điểm dữ liệu.
public record CmsRevenueTimelineResponse(
        Instant from, // Thời điểm bắt đầu được truy vấn.
        Instant to, // Thời điểm kết thúc được truy vấn.
        String granularity, // Cách gom nhóm: ngày, tuần hoặc tháng.
        List<CmsRevenueBucket> buckets // Danh sách các mốc doanh thu.
) {}
