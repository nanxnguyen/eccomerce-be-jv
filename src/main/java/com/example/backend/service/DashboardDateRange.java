package com.example.backend.service;

import java.time.Instant;

public record DashboardDateRange(
        Instant from, // Thời điểm bắt đầu lấy dữ liệu.
        Instant to // Thời điểm kết thúc lấy dữ liệu.
) {}
