package com.erp.timetable.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request to create a tenant college together with its College Admin account
 * (POST /colleges, SUPER_ADMIN only). Backend-side creation only — the request
 * never carries a collegeId of an existing tenant.
 */
@Data
public class CollegeRequest {

    @NotBlank(message = "College name is required")
    @Size(max = 255, message = "College name cannot exceed 255 characters")
    private String name;

    /** Short unique college code — also used to derive the admin login ID. */
    @NotBlank(message = "College code is required")
    @Size(max = 50, message = "College code cannot exceed 50 characters")
    private String code;

    @Size(max = 2000, message = "College address cannot exceed 2000 characters")
    private String address;

    @Size(max = 20, message = "College phone cannot exceed 20 characters")
    private String phone;

    @Size(max = 255, message = "College email cannot exceed 255 characters")
    private String email;

    /** The College Admin login ID (username). Defaults to {@code <code>ADMIN} when blank. */
    @Size(min = 3, max = 50, message = "College Admin login ID must be 3-50 characters")
    private String collegeId;

    @NotBlank(message = "College Admin password is required")
    @Size(min = 6, max = 72, message = "College Admin password must be 6-72 characters")
    private String password;
}