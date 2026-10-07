package com.erp.timetable.module.auth.service;

import com.erp.timetable.common.exception.BusinessException;
import com.erp.timetable.common.exception.ResourceNotFoundException;
import com.erp.timetable.module.auth.dto.CollegeAdminAccountRequest;
import com.erp.timetable.module.auth.dto.CollegeRegistrationRequest;
import com.erp.timetable.module.auth.dto.CollegeRequest;
import com.erp.timetable.module.auth.dto.CollegeResponse;
import com.erp.timetable.module.auth.entity.College;
import com.erp.timetable.module.auth.entity.Role;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.CollegeRepository;
import com.erp.timetable.module.auth.repository.RoleRepository;
import com.erp.timetable.module.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Tenant college lifecycle. Only {@code SUPER_ADMIN} may create a college
 * (enforced at the controller). The college and its College Admin account are
 * created atomically; the admin user is permanently bound to the college.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CollegeService {

    private final CollegeRepository collegeRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public CollegeResponse createCollege(CollegeRequest request) {
        if (collegeRepository.existsByCode(request.getCode().trim())) {
            throw new BusinessException("College code '" + request.getCode() + "' is already in use");
        }

        String username = request.getCollegeId() != null && !request.getCollegeId().isBlank()
            ? request.getCollegeId().trim()
            : request.getCode().trim().toUpperCase() + "ADMIN";

        // Login IDs are unique per college. A brand-new college has no users,
        // so its first admin can never collide within itself — the composite
        // UK (college_id, username) is what guards duplicates from now on.
        String email = request.getEmail();
        if (email == null || email.isBlank()) {
            email = username.toLowerCase() + "@college.edu";
        }

        College college = collegeRepository.save(College.builder()
            .name(request.getName().trim())
            .code(request.getCode().trim().toUpperCase())
            .address(request.getAddress())
            .phone(request.getPhone())
            .email(email)
            .isActive(true)
            .build());

        Role collegeAdminRole = roleRepository.findByName(RoleName.ROLE_COLLEGE_ADMIN)
            .orElseThrow(() -> new BusinessException("College Admin role is not configured"));

        User admin = User.builder()
            .username(username)
            .email(email)
            .password(passwordEncoder.encode(request.getPassword()))
            .fullName(request.getName().trim() + " Admin")
            .college(college)
            .isActive(true)
            .build();
        admin.addRole(collegeAdminRole);
        User savedAdmin = userRepository.save(admin);

        log.info("College '{}' ({}) created with College Admin login '{}'",
            college.getName(), college.getCode(), username);

        return toResponse(college, savedAdmin);
    }

    @Transactional(readOnly = true)
    public List<CollegeResponse> listColleges() {
        return collegeRepository.findAll().stream()
            .map(c -> toResponse(c, null))
            .toList();
    }

    /**
     * Public first-time college registration: creates a new college AND its
     * first College Admin account atomically, reusing {@link #createCollege}.
     * No existing account is required (the controller endpoint is public).
     */
    @Transactional
    public CollegeResponse registerCollege(CollegeRegistrationRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("Password and confirm password do not match");
        }
        return createCollege(request);
    }

    /**
     * Standalone "Create College Admin Account" function: creates a College
     * Admin login ID + password bound to a SPECIFIC existing college.
     * The account is anchored to the college (ROLE_COLLEGE_ADMIN) so the
     * normal login flow and server-side tenant isolation apply to it.
     */
    @Transactional
    public CollegeResponse createCollegeAdminAccount(Long collegeId, CollegeAdminAccountRequest request) {
        College college = collegeRepository.findById(collegeId)
            .orElseThrow(() -> new ResourceNotFoundException("College", collegeId));

        String username = request.getLoginId().trim();
        // Scoped to THIS college: the same Login ID is fine in another college.
        if (userRepository.existsByUsernameForCollege(username, college.getId())) {
            throw new BusinessException("College Admin login ID '" + username + "' is already in use in this college");
        }

        String email = username.toLowerCase() + "@college.edu";
        if (userRepository.existsByEmailForCollege(email, college.getId())) {
            throw new BusinessException("College Admin email '" + email + "' is already in use in this college");
        }

        Role collegeAdminRole = roleRepository.findByName(RoleName.ROLE_COLLEGE_ADMIN)
            .orElseThrow(() -> new BusinessException("College Admin role is not configured"));

        User admin = User.builder()
            .username(username)
            .email(email)
            .password(passwordEncoder.encode(request.getPassword()))
            .fullName(college.getName() + " Admin")
            .college(college)
            .isActive(true)
            .build();
        admin.addRole(collegeAdminRole);
        User savedAdmin = userRepository.save(admin);

        log.info("College Admin login '{}' created for college '{}' ({})",
            username, college.getName(), college.getCode());

        return toResponse(college, savedAdmin);
    }

    private CollegeResponse toResponse(College c, User admin) {
        return CollegeResponse.builder()
            .id(c.getId())
            .name(c.getName())
            .code(c.getCode())
            .address(c.getAddress())
            .phone(c.getPhone())
            .email(c.getEmail())
            .adminUsername(admin != null ? admin.getUsername() : null)
            .adminUserId(admin != null ? admin.getId() : null)
            .build();
    }
}