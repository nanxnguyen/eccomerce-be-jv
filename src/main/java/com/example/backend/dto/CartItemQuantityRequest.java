package com.example.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CartItemQuantityRequest(@NotNull @Min(1) Integer quantity) {}
