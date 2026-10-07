package com.erp.timetable.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Login request body: accepts username or email + password. Login IDs are
 * unique PER COLLEGE, so the SAME Login ID may exist in several colleges
 * (e.g. an HOD Login ID like CSDTamil in colleges A and B). No college code
 * is required — the password belongs to the individual account and is what
 * determines which account is being authenticated.
 */
@Data
public class LoginRequest {

    @NotBlank(message = "Username or email is required")
    private String usernameOrEmail;

    @NotBlank(message = "Password is required")
    private String password;
}
