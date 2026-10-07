package com.erp.timetable.module.auth.dto;

import lombok.Data;

/**
 * Optional body for {@code DELETE /me/sessions/all}. When {@code
 * refreshToken} is present, the session holding that token is KEPT and every
 * other session is revoked (logout-from-other-devices); when absent, ALL of
 * the user's sessions are revoked.
 */
@Data
public class RevokeSessionsRequest {

    private String refreshToken;
}