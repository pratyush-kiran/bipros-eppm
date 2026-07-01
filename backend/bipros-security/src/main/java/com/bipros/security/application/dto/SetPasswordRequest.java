package com.bipros.security.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Admin password-reset payload: set {@code password} for the user identified by {@code username}. */
public record SetPasswordRequest(
    @NotBlank String username,
    @NotBlank @Size(min = 6, max = 100) String password
) {}
