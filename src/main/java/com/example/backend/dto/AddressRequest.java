package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record AddressRequest(
        @NotBlank String recipientName,
        @NotBlank String phone,
        @NotBlank String addressLine,
        String ward,
        String district,
        String province
) {}
