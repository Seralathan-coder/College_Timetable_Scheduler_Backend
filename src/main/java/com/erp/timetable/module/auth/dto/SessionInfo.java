package com.erp.timetable.module.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * One active session entry in {@code GET /me/sessions}. Exposes no secrets —
 * the refresh token itself is never returned by any API.
 */
@Data
@Builder
public class SessionInfo {

    private Long sessionId;
    private String device;
    private String ipAddress;
    private Instant createdAt;
    private Instant expiresAt;
    private boolean active;
}