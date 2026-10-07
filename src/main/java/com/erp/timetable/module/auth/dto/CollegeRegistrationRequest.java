package com.erp.timetable.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for the public first-time college registration flow
 * (POST /auth/register-college). A new college + its FIRST College Admin
 * account are created atomically; no existing account is required. All
 * college + admin fields and validation are inherited from
 * {@link CollegeRequest} (reused for the actual creation), adding only the
 * confirmation-password value.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CollegeRegistrationRequest extends CollegeRequest {

    @NotBlank(message = "Please confirm the College Admin password")
    @Size(min = 6, max = 72, message = "College Admin password must be 6-72 characters")
    private String confirmPassword;
}