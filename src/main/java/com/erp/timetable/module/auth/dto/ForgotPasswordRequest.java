package com.erp.timetable.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Forgot-password request body: the account is located by its login
 * identifier (username or email). Login IDs are unique PER COLLEGE, so a
 * reset for an identifier held by more than one account is rejected instead
 * of resetting the wrong tenant's password.
 */
@Data
public class ForgotPasswordRequest {

    @NotBlank(message = "Username or email is required")
    private String usernameOrEmail;
}