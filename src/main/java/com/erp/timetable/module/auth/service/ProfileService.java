package com.erp.timetable.module.auth.service;

import com.erp.timetable.common.exception.BusinessException;
import com.erp.timetable.common.exception.ResourceNotFoundException;
import com.erp.timetable.common.file.FileStorageService;
import com.erp.timetable.module.auth.dto.ChangePasswordRequest;
import com.erp.timetable.module.auth.dto.ProfileResponse;
import com.erp.timetable.module.auth.dto.SessionInfo;
import com.erp.timetable.module.auth.dto.UpdateProfileRequest;
import com.erp.timetable.module.auth.dto.UploadPhotoResponse;
import com.erp.timetable.module.auth.entity.Institution;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.entity.UserSession;
import com.erp.timetable.module.auth.repository.InstitutionRepository;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.auth.repository.UserSessionRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileService {

    private final UserRepository userRepository;
    private final FacultyRepository facultyRepository;
    private final InstitutionRepository institutionRepository;
    private final UserSessionRepository userSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;

    // ── Read own profile ───────────────────────────────────────────────
    @Transactional(readOnly = true)
    public ProfileResponse getProfile(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("User not found"));
        return toResponse(user);
    }

    // ── Update own profile (whitelist enforced by DTO shape) ────────────
    @Transactional
    public ProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("User not found"));

        // Apply ONLY the whitelisted fields. role / isActive / department /
        // username / email / employeeId / designation are NOT settable here —
        // they have no field in UpdateProfileRequest, so they cannot arrive.
        user.setFullName(request.getFullName());
        user.setPhone(request.getPhone());
        user.setProfilePhotoUrl(request.getProfilePhotoUrl());
        userRepository.save(user);

        log.info("User {} updated own profile", userId);
        return toResponse(user);
    }

    // ── Change own password ─────────────────────────────────────────────
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("User not found"));

        // The current password is verified here, in the service, against the
        // stored hash — this is the ONLY way to change the password. A request
        // with a wrong current password is rejected outright and never reaches
        // the encoding step. This gate is what stops a stolen live session (or
        // a CSRF-style write on an unlocked device) from silently rotating the
        // account's password.
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new BusinessException("Current password is incorrect");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        log.info("User {} changed own password", userId);
    }

    // ── Profile photo (Phase 6 — file upload) ──────────────────────────
    @Transactional
    public UploadPhotoResponse uploadPhoto(Long userId, MultipartFile file) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("User not found"));

        String newUrl = fileStorageService.storeProfilePhoto(file);
        String oldUrl = user.getProfilePhotoUrl();

        user.setProfilePhotoUrl(newUrl);
        userRepository.save(user);
        log.info("User {} uploaded profile photo {}", userId, newUrl);

        // Replace-flow hygiene: an old local photo no longer referenced is removed.
        if (oldUrl != null && !oldUrl.isBlank() && !oldUrl.equals(newUrl)) {
            fileStorageService.delete(oldUrl);
        }
        return UploadPhotoResponse.builder().profilePhotoUrl(newUrl).build();
    }

    // ── Sessions (Phase 5 — multi-device) ──────────────────────────────

    /** Active sessions for the current user (newest first), no refresh tokens exposed. */
    @Transactional(readOnly = true)
    public List<SessionInfo> getSessions(Long userId) {
        return userSessionRepository.findActiveByUserId(userId, Instant.now())
            .stream()
            .map(this::toSessionInfo)
            .toList();
    }

    /** Revoke ONE session of the current user — ownership enforced, 404 otherwise. */
    @Transactional
    public void revokeSession(Long userId, Long sessionId) {
        UserSession session = userSessionRepository.findByIdAndUserId(sessionId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("User session", "id", sessionId));
        session.setIsRevoked(true);
        userSessionRepository.save(session);
        log.info("User {} revoked session {}", userId, sessionId);
    }

    /**
     * Revoke all of the user's sessions EXCEPT the one holding
     * {@code keepRefreshToken} (logout-from-other-devices). When the token is
     * null/blank, ALL sessions are revoked (fallback that matches the
     * pre-Phase-5 single-session logout).
     */
    @Transactional
    public void revokeAllOtherSessions(Long userId, String keepRefreshToken) {
        List<UserSession> sessions = userSessionRepository.findByUserId(userId);
        boolean keep = keepRefreshToken != null && !keepRefreshToken.isBlank();
        sessions.stream()
            .filter(s -> !keep || !keepRefreshToken.equals(s.getRefreshToken()))
            .forEach(s -> s.setIsRevoked(true));
        userSessionRepository.saveAll(sessions);
        log.info("User {} revoked {} session(s), {}", userId,
            sessions.size(), keep ? "keeping the current device" : "all devices");
    }

    // ── Mapping ────────────────────────────────────────────────────────
    private ProfileResponse toResponse(User user) {
        Faculty faculty = user.getId() != null
            ? facultyRepository.findByUserId(user.getId()).orElse(null)
            : null;

        // The user's OWN college when available, with a legacy fallback to the old
        // single-row Institution for pre-multi-college accounts (display only —
        // isolation never depends on this).
        String institutionName = null;
        String institutionAddress = null;
        if (user.getCollege() != null) {
            institutionName = user.getCollege().getName();
            institutionAddress = user.getCollege().getAddress();
        } else {
            Institution institution = institutionRepository
                .findById(Institution.SINGLETON_ID).orElse(null);
            institutionName = institution != null ? institution.getName() : null;
            institutionAddress = institution != null ? institution.getAddress() : null;
        }

        return ProfileResponse.builder()
            .userId(user.getId())
            .username(user.getUsername())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .phone(user.getPhone())
            .profilePhotoUrl(photoUrlOrNull(user))
            .departmentId(user.getDepartment() != null ? user.getDepartment().getId() : null)
            .departmentName(user.getDepartment() != null ? user.getDepartment().getName() : null)
            .institutionName(institutionName)
            .institutionAddress(institutionAddress)
            .employeeId(faculty != null ? faculty.getEmployeeId() : null)
            .designation(faculty != null ? faculty.getDesignation() : null)
            .roles(user.getRoles().stream().map(r -> r.getName().name()).toList())
            .isActive(user.getIsActive())
            .joinedDate(user.getCreatedAt())
            .lastLoginAt(user.getLastLoginAt())
            .activeSessionCount(userSessionRepository
                .countActiveByUserId(user.getId(), Instant.now()))
            .build();
    }

    /**
     * Phase 6 graceful photo fallback: if the stored photo file is missing on
     * disk (e.g. the upload directory was cleared but the DB row remains), the
     * profile degrades to the letter-initial avatar — the URL is omitted,
     * never a broken image, never an error.
     */
    private String photoUrlOrNull(User user) {
        String url = user.getProfilePhotoUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        return fileStorageService.exists(url) ? url : null;
    }

    private SessionInfo toSessionInfo(UserSession s) {
        boolean active = !Boolean.TRUE.equals(s.getIsRevoked())
            && s.getExpiresAt() != null
            && s.getExpiresAt().isAfter(Instant.now());
        return SessionInfo.builder()
            .sessionId(s.getId())
            .device(s.getDeviceInfo())
            .ipAddress(s.getIpAddress())
            .createdAt(s.getCreatedAt())
            .expiresAt(s.getExpiresAt())
            .active(active)
            .build();
    }
}