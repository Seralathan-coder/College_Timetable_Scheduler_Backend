package com.erp.timetable.module.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Full "My Profile" payload for the authenticated user.
 * Identity is derived from the JWT principal server-side, never from the client.
 */
@Data
@Builder
public class ProfileResponse {

    private Long userId;
    private String username;
    private String email;
    private String fullName;
    private String phone;
    private String profilePhotoUrl;

    // ── College Information ────────────────────────────────────────────
    private Long departmentId;
    private String departmentName;

    // Global institution (single row, read-only; null only if not configured)
    private String institutionName;
    private String institutionAddress;

    // Faculty-linked fields (null when the user has no Faculty record)
    private String employeeId;
    private String designation;

    // ── Account Information ────────────────────────────────────────────
    private List<String> roles;
    private Boolean isActive;
    private Instant joinedDate;      // maps from User.createdAt (audit)
    private Instant lastLoginAt;
    private long activeSessionCount; // populated from Phase 5 (user_sessions); 0 until then
}