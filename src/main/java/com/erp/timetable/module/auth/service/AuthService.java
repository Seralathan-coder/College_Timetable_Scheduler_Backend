package com.erp.timetable.module.auth.service;

import com.erp.timetable.common.exception.BusinessException;
import com.erp.timetable.module.auth.dto.ForgotPasswordRequest;
import com.erp.timetable.module.auth.dto.LoginRequest;
import com.erp.timetable.module.auth.dto.LoginResponse;
import com.erp.timetable.module.auth.dto.RefreshTokenRequest;
import com.erp.timetable.module.auth.dto.ResetPasswordRequest;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.UserSession;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.auth.repository.UserSessionRepository;
import com.erp.timetable.module.auth.security.JwtTokenProvider;
import com.erp.timetable.module.auth.security.UserPrincipal;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles login, token refresh, and logout operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final FacultyRepository facultyRepository;

    // ── Login ──────────────────────────────────────────────────────────
    @Transactional
    public LoginResponse login(LoginRequest request, String deviceInfo, String ipAddress) {
        String identifier = request.getUsernameOrEmail() == null ? "" : request.getUsernameOrEmail().trim();

        // Login IDs are unique PER COLLEGE — two colleges may share the SAME
        // Login ID (e.g. an HOD Login ID like CSDTamil in colleges A and B).
        // No college code is required: the password belongs to the individual
        // account, so it is what determines WHICH account is being
        // authenticated. Only active accounts are candidates; every failure
        // path returns the same generic 401 so the existence of an account (or
        // of a counterpart in another college) is never revealed.
        List<User> activeCandidates = userRepository.findAllByUsernameOrEmail(identifier).stream()
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .toList();
        if (activeCandidates.isEmpty()) {
            throw new BadCredentialsException("Invalid username/email or password");
        }

        List<User> passwordMatches = activeCandidates.stream()
            .filter(u -> passwordEncoder.matches(request.getPassword(), u.getPassword()))
            .toList();
        if (passwordMatches.isEmpty()) {
            throw new BadCredentialsException("Invalid username/email or password");
        }
        // Two accounts with the same Login ID AND the same password are
        // genuinely ambiguous — never pick one arbitrarily.
        if (passwordMatches.size() > 1) {
            throw new BusinessException(
                "Multiple accounts match these credentials. Please contact your college administrator.");
        }

        User user = passwordMatches.get(0);
        UserPrincipal principal = UserPrincipal.build(user);
        var authentication = new UsernamePasswordAuthenticationToken(
            principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String accessToken  = jwtTokenProvider.generateAccessToken(authentication);
        String refreshToken = jwtTokenProvider.generateRefreshToken();

        // Phase 5: refresh tokens live in per-login user_sessions rows, so a
        // second device does NOT overwrite the first device's session.
        // users.refresh_token / refresh_token_expiry are no longer written.

        // Student access has been removed from the application workflow. The
        // DB records are preserved, but the account can no longer sign in.
        if (user.hasRole(RoleName.ROLE_STUDENT)) {
            throw new BusinessException(
                "Student login is not available. Please contact your college administration.");
        }

        userSessionRepository.save(UserSession.builder()
            .user(user)
            .refreshToken(refreshToken)
            .deviceInfo(deviceInfo)
            .ipAddress(ipAddress)
            .expiresAt(Instant.now().plusMillis(jwtTokenProvider.getRefreshExpirationMs()))
            .build());
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        List<String> roles = principal.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList();

        log.info("User '{}' logged in successfully", principal.getUsername());

        return LoginResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .tokenType("Bearer")
            .expiresIn(jwtTokenProvider.getRefreshExpirationMs() / 1000)
            .userId(principal.getId())
            .username(principal.getUsername())
            .email(principal.getEmail())
            .fullName(user.getFullName())
            .phone(user.getPhone())
            .profilePhotoUrl(user.getProfilePhotoUrl())
            .departmentId(user.getDepartment() != null ? user.getDepartment().getId() : null)
            .departmentName(user.getDepartment() != null ? user.getDepartment().getName() : null)
            .collegeId(user.getCollege() != null ? user.getCollege().getId() : null)
            .collegeName(user.getCollege() != null ? user.getCollege().getName() : null)
            .employeeId(employeeIdOrNull(user))
            .designation(designationOrNull(user))
            .roles(roles)
            .build();
    }

    // ── Refresh Token ──────────────────────────────────────────────────
    @Transactional
    public LoginResponse refreshToken(RefreshTokenRequest request) {
        UserSession session = userSessionRepository.findByRefreshToken(request.getRefreshToken())
            .orElseThrow(() -> new BusinessException("Invalid refresh token"));

        if (Boolean.TRUE.equals(session.getIsRevoked())) {
            throw new BusinessException("Invalid refresh token");
        }
        if (session.getExpiresAt() == null ||
                session.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException("Refresh token has expired. Please login again.");
        }

        User user = session.getUser();

        List<String> roles = user.getRoles().stream()
            .map(r -> r.getName().name())
            .toList();

        String newAccessToken = jwtTokenProvider.generateAccessTokenFromUsername(
            user.getUsername(), user.getId(), user.getEmail(), roles,
            user.getCollege() != null ? user.getCollege().getId() : null);

        // Rotate the token INSIDE the same session row — the device keeps its
        // identity, only the credential changes.
        String newRefreshToken = jwtTokenProvider.generateRefreshToken();
        session.setRefreshToken(newRefreshToken);
        session.setExpiresAt(
            Instant.now().plusMillis(jwtTokenProvider.getRefreshExpirationMs()));
        userSessionRepository.save(session);

        return LoginResponse.builder()
            .accessToken(newAccessToken)
            .refreshToken(newRefreshToken)
            .tokenType("Bearer")
            .expiresIn(jwtTokenProvider.getRefreshExpirationMs() / 1000)
            .userId(user.getId())
            .username(user.getUsername())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .phone(user.getPhone())
            .profilePhotoUrl(user.getProfilePhotoUrl())
            .departmentId(user.getDepartment() != null ? user.getDepartment().getId() : null)
            .departmentName(user.getDepartment() != null ? user.getDepartment().getName() : null)
            .collegeId(user.getCollege() != null ? user.getCollege().getId() : null)
            .collegeName(user.getCollege() != null ? user.getCollege().getName() : null)
            .employeeId(employeeIdOrNull(user))
            .designation(designationOrNull(user))
            .roles(roles)
            .build();
    }

    // ── Logout ─────────────────────────────────────────────────────────
    /** Revokes one session when a refresh token is supplied, all otherwise. */
    @Transactional
    public void logout(Long userId, String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            userSessionRepository.findByRefreshToken(refreshToken)
                .filter(s -> s.getUser().getId().equals(userId))
                .ifPresent(s -> {
                    s.setIsRevoked(true);
                    userSessionRepository.save(s);
                });
        } else {
            revokeAllForUser(userId);
        }
        log.info("User {} logged out, refresh token(s) revoked", userId);
    }

    private void revokeAllForUser(Long userId) {
        userSessionRepository.findByUserId(userId).forEach(s -> s.setIsRevoked(true));
    }

    // ── Forgot / Reset password ────────────────────────────────────────
    /**
     * Issues a one-time password-reset token for the account that owns the
     * given login identifier. Only the SHA-256 hash of the token is stored —
     * the raw token travels back to the responder exactly once and is never
     * persisted. No email/SMTP backend exists in this build, so the raw token
     * is returned in the response body (dev-mode delivery); the account pinning
     * and expiry rules are the same as a mailed link would use.
     */
    @Transactional
    public Map<String, Object> requestPasswordReset(ForgotPasswordRequest request) {
        String identifier = request.getUsernameOrEmail() == null ? "" : request.getUsernameOrEmail().trim();
        List<User> activeCandidates = userRepository.findAllByUsernameOrEmail(identifier).stream()
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .toList();
        // A password reset has no password to disambiguate with, so an
        // identifier shared by several colleges is rejected outright rather
        // than resetting the wrong tenant's account.
        if (activeCandidates.size() > 1) {
            throw new BusinessException(
                "Multiple accounts use this login identifier. Please contact your college administrator.");
        }
        if (activeCandidates.isEmpty()) {
            // Generic success: never reveal whether the identifier exists.
            Map<String, Object> empty = new HashMap<>();
            empty.put("resetToken", null);
            return empty;
        }

        User user = activeCandidates.get(0);
        String rawToken = jwtTokenProvider.generateRefreshToken();
        user.setPasswordResetToken(sha256Hex(rawToken));
        user.setPasswordResetExpiry(Instant.now().plus(RESET_TOKEN_TTL));
        userRepository.save(user);
        log.info("Password reset token issued for user {}", user.getId());
        return Map.of("resetToken", rawToken);
    }

    /** Validates the one-time token and sets the new password (single use, time-boxed). */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String hashed = sha256Hex(request.getToken());
        User user = userRepository.findByPasswordResetToken(hashed)
            .orElseThrow(() -> new BusinessException(
                "Invalid or expired reset token. Please request a new password reset."));
        if (user.getPasswordResetExpiry() == null
                || user.getPasswordResetExpiry().isBefore(Instant.now())) {
            throw new BusinessException(
                "Invalid or expired reset token. Please request a new password reset.");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiry(null);
        // The reset flow knows no device: every existing session dies so all
        // logged-in devices must sign in again with the new password.
        userSessionRepository.findByUserId(user.getId()).forEach(s -> s.setIsRevoked(true));
        userRepository.save(user);
        log.info("Password reset completed for user {}", user.getId());
    }

    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(15);

    /** SHA-256 hex digest — used to store the reset token HASHED, never raw. */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── Linked Faculty (profile fields for the login response) ─────────
    private Faculty linkedFaculty(User user) {
        if (user.getId() == null) {
            return null;
        }
        return facultyRepository.findByUserId(user.getId()).orElse(null);
    }

    private String employeeIdOrNull(User user) {
        Faculty f = linkedFaculty(user);
        return f != null ? f.getEmployeeId() : null;
    }

    private String designationOrNull(User user) {
        Faculty f = linkedFaculty(user);
        return f != null ? f.getDesignation() : null;
    }
}
