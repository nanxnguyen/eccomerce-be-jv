package com.example.backend.dto;

import com.example.backend.entity.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record OrderStatusUpdateRequest(@NotNull OrderStatus status) {}
