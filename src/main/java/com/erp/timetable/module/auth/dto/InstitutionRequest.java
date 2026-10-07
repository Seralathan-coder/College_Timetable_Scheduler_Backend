package com.erp.timetable.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request to create/update the global institution (SUPER_ADMIN only).
 * Whitelist: name and address ONLY. No id, no ownership, no association.
 */
@Data
public class InstitutionRequest {

    @NotBlank(message = "Institution name is required")
    @Size(max = 255, message = "Institution name cannot exceed 255 characters")
    private String name;

    @Size(max = 50, message = "Institution code cannot exceed 50 characters")
    private String code;

    @Size(max = 2000, message = "Institution address cannot exceed 2000 characters")
    private String address;

    @Size(max = 20, message = "Institution phone cannot exceed 20 characters")
    private String phone;

    @Size(max = 255, message = "Institution email cannot exceed 255 characters")
    private String email;
}