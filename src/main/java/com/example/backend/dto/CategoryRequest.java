package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record CategoryRequest(@NotBlank String name, @NotBlank String slug, String description) {}
