package com.erp.timetable.module.auth.dto;

import lombok.Data;

/**
 * Optional body for {@code POST /auth/logout}. When {@code refreshToken} is
 * present, ONLY that device's session is revoked; when absent, ALL of the
 * user's sessions are revoked (backward-compatible with the pre-Phase-5
 * single-session logout).
 */
@Data
public class LogoutRequest {

    private String refreshToken;
}