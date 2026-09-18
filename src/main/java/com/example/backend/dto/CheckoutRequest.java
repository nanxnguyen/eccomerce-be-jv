package com.example.backend.dto;

import com.example.backend.entity.PaymentMethod;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CheckoutRequest(
        @NotEmpty List<Long> cartItemIds,
        @NotNull Long addressId,
        @NotNull PaymentMethod paymentMethod
) {}
