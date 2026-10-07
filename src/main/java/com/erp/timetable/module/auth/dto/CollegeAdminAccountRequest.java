package com.erp.timetable.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request to create the College Admin login ID + password for a SPECIFIC
 * existing college (POST /colleges/{id}/admin-account, SUPER_ADMIN only).
 * The created account is bound to that college with ROLE_COLLEGE_ADMIN and
 * signs in through the normal /auth/login flow.
 */
@Data
public class CollegeAdminAccountRequest {

    /** The College Admin login ID (username) for the college. */
    @NotBlank(message = "College Admin login ID is required")
    @Size(min = 3, max = 50, message = "College Admin login ID must be 3-50 characters")
    private String loginId;

    /** The College Admin login password. */
    @NotBlank(message = "College Admin password is required")
    @Size(min = 6, max = 72, message = "College Admin password must be 6-72 characters")
    private String password;
}