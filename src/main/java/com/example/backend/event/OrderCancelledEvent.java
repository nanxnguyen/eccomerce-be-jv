package com.example.backend.event;

public record OrderCancelledEvent(Long orderId, String reason) {}
