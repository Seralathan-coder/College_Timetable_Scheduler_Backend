package com.erp.timetable.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Editable "my profile" fields — STRICT WHITELIST of what a user may change
 * about themselves through PUT /me.
 *
 * SECURITY: this DTO is the whitelist. It deliberately contains ONLY the
 * genuinely personal fields (fullName, phone, profilePhotoUrl). Fields that
 * must NEVER be user-writable through /me — role, isActive, department,
 * employeeId, designation, username, email — have NO field here, so they
 * cannot be present in a deserialized request at all. Jackson ignores unknown
 * JSON properties, so a client sending {"role": "ROLE_SUPER_ADMIN"} gets it
 * silently dropped (never read, never compared, never applied).
 *
 * A user editing their own role or department through a profile update would
 * be a privilege-escalation bug; this DTO structurally prevents it.
 */
@Data
public class UpdateProfileRequest {

    @NotBlank(message = "Full name cannot be blank")
    @Size(max = 200, message = "Full name must be at most 200 characters")
    private String fullName;

    @Size(max = 20, message = "Phone must be at most 20 characters")
    private String phone;

    @Size(max = 500, message = "Profile photo URL must be at most 500 characters")
    private String profilePhotoUrl;
}