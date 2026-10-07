package com.erp.timetable.module.auth.controller;

import com.erp.timetable.common.response.ApiResponse;
import com.erp.timetable.module.auth.dto.CollegeRegistrationRequest;
import com.erp.timetable.module.auth.dto.CollegeResponse;
import com.erp.timetable.module.auth.dto.ForgotPasswordRequest;
import com.erp.timetable.module.auth.dto.LoginRequest;
import com.erp.timetable.module.auth.dto.LoginResponse;
import com.erp.timetable.module.auth.dto.LogoutRequest;
import com.erp.timetable.module.auth.dto.RefreshTokenRequest;
import com.erp.timetable.module.auth.dto.ResetPasswordRequest;
import com.erp.timetable.module.auth.security.UserPrincipal;
import com.erp.timetable.module.auth.service.AuthService;
import com.erp.timetable.module.auth.service.CollegeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for authentication operations.
 * All endpoints under /auth/** are publicly accessible (no JWT required).
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Login, refresh, and logout endpoints")
public class AuthController {

    private final AuthService authService;
    private final CollegeService collegeService;

    /**
     * PUBLIC first-time registration (no login required). Creates a new
     * college + its first College Admin account in a single transaction.
     */
    @PostMapping("/register-college")
    @Operation(summary = "Register a new college with its first College Admin account (public)")
    public ResponseEntity<ApiResponse<CollegeResponse>> registerCollege(
            @Valid @RequestBody CollegeRegistrationRequest request) {
        CollegeResponse response = collegeService.registerCollege(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("College registered successfully. Sign in with your College Admin Login ID.", response));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate user and obtain JWT token pair")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        LoginResponse response = authService.login(
            request,
            resolveDeviceInfo(servletRequest),
            resolveClientIp(servletRequest));
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh JWT access token using a valid refresh token")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest request) {
        LoginResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(ApiResponse.success("Token refreshed", response));
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Request a one-time password reset token (public)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
            "If the account exists, a reset token has been issued.", authService.requestPasswordReset(request)));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Set a new password using a one-time reset token (public)")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.successMessage(
            "Password has been reset. Please sign in with your new password."));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Logout — revoke ONE device's session (body refreshToken) or ALL sessions (no body)")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody(required = false) LogoutRequest request) {
        authService.logout(principal.getId(),
            request != null ? request.getRefreshToken() : null);
        return ResponseEntity.ok(ApiResponse.successMessage("Logged out successfully"));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get currently authenticated user's profile")
    public ResponseEntity<ApiResponse<UserPrincipal>> me(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(principal));
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /** Client IP, honouring a reverse proxy's X-Forwarded-For. */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** User-Agent as the human-readable device label, capped at the column width. */
    private String resolveDeviceInfo(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent == null || agent.isBlank()) {
            return "Unknown device";
        }
        return agent.length() > 300 ? agent.substring(0, 300) : agent;
    }
}
