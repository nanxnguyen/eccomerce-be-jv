package com.example.backend.service;

import java.time.Instant; // kiểu/thao tác thời gian chuẩn Java (Instant).

public record DashboardDateRange(Instant from, Instant to) {}
