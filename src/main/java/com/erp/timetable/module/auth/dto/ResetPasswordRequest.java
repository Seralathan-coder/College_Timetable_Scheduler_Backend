package com.erp.timetable.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Reset-password request body: the one-time token that was returned by
 * {@code POST /auth/forgot-password} plus the desired new password. There is
 * deliberately NO user identifier here — the token alone pins the account, so
 * a client can never reset another user's password by naming them.
 */
@Data
public class ResetPasswordRequest {

    @NotBlank(message = "Reset token is required")
    private String token;

    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 128, message = "New password must be between 8 and 128 characters")
    private String newPassword;
}