package com.erp.timetable.module.auth.controller;

import com.erp.timetable.common.response.ApiResponse;
import com.erp.timetable.module.auth.dto.ChangePasswordRequest;
import com.erp.timetable.module.auth.dto.ProfileResponse;
import com.erp.timetable.module.auth.dto.RevokeSessionsRequest;
import com.erp.timetable.module.auth.dto.SessionInfo;
import com.erp.timetable.module.auth.dto.UpdateProfileRequest;
import com.erp.timetable.module.auth.dto.UploadPhotoResponse;
import com.erp.timetable.module.auth.security.UserPrincipal;
import com.erp.timetable.module.auth.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * "My Profile" endpoints.
 *
 * SECURITY: the current user's identity is derived EXCLUSIVELY from the
 * authenticated JWT principal (@AuthenticationPrincipal). There is NO
 * @RequestParam, @PathVariable, or request-body user ID anywhere in these
 * handlers — a client cannot target another user by changing an ID in the
 * request, because no ID is read from the request at all.
 */
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
@Tag(name = "Profile", description = "Current user's own profile (identity from JWT only)")
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get currently authenticated user's full profile")
    public ResponseEntity<ApiResponse<ProfileResponse>> getProfile(
            @AuthenticationPrincipal UserPrincipal principal) {
        ProfileResponse response = profileService.getProfile(principal.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Update own profile — whitelisted fields only (fullName, phone, profilePhotoUrl)")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        ProfileResponse response = profileService.updateProfile(principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.success("Profile updated", response));
    }

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Change own password — REQUIRES the current password, verified against the stored hash")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        profileService.changePassword(principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.successMessage("Password changed successfully"));
    }

    @PostMapping(value = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Upload own profile photo (multipart, JPG/PNG/WebP, max 5 MB) — returns the photo URL")
    public ResponseEntity<ApiResponse<UploadPhotoResponse>> uploadPhoto(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestPart("file") MultipartFile file) {
        UploadPhotoResponse response = profileService.uploadPhoto(principal.getId(), file);
        return ResponseEntity.ok(ApiResponse.success("Photo uploaded", response));
    }

    @GetMapping("/sessions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List the current user's active sessions (device, IP, created) — identity from JWT only")
    public ResponseEntity<ApiResponse<List<SessionInfo>>> getSessions(
            @AuthenticationPrincipal UserPrincipal principal) {
        List<SessionInfo> sessions = profileService.getSessions(principal.getId());
        return ResponseEntity.ok(ApiResponse.success(sessions));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Revoke one of the current user's sessions — ownership enforced, 404 if not owned")
    public ResponseEntity<ApiResponse<Void>> revokeSession(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long sessionId) {
        profileService.revokeSession(principal.getId(), sessionId);
        return ResponseEntity.ok(ApiResponse.successMessage("Session revoked"));
    }

    @DeleteMapping("/sessions/all")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Revoke all OTHER sessions; keep the one holding the supplied refresh token when present")
    public ResponseEntity<ApiResponse<Void>> revokeAllOtherSessions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody(required = false) RevokeSessionsRequest request) {
        profileService.revokeAllOtherSessions(principal.getId(),
            request != null ? request.getRefreshToken() : null);
        return ResponseEntity.ok(ApiResponse.successMessage("Other sessions revoked"));
    }
}