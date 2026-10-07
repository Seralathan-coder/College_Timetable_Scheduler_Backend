package com.erp.timetable.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Change-password request body: the user must supply their CURRENT password
 * (verified server-side against the stored hash) plus the desired new password.
 *
 * SECURITY: there is deliberately NO user ID here. Identity comes exclusively
 * from the authenticated JWT principal — a client cannot change another user's
 * password by sending an ID, because no ID is ever read from the request.
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "Current password is required")
    private String currentPassword;

    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 128, message = "New password must be between 8 and 128 characters")
    private String newPassword;
}