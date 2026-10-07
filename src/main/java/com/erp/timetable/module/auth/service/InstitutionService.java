package com.erp.timetable.module.auth.service;

import com.erp.timetable.config.security.TenantContext;
import com.erp.timetable.common.exception.BusinessException;
import com.erp.timetable.module.auth.dto.InstitutionRequest;
import com.erp.timetable.module.auth.dto.InstitutionResponse;
import com.erp.timetable.module.auth.entity.College;
import com.erp.timetable.module.auth.entity.Institution;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.CollegeRepository;
import com.erp.timetable.module.auth.repository.InstitutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * College/institution read/update logic, now tenant-scoped.
 *
 * <p>GET returns the CURRENT USER's college; PUT updates that same college.
 * College Admins edit their own college; SUPER_ADMIN edits the college their
 * own dev account belongs to (the default college). Authorization is enforced
 * at the controller boundary via {@code @PreAuthorize} — no id ever arrives
 * from the client, so a caller can never point the endpoint at another
 * college. Legacy pre-multi-college rows fall back to the single {@link
 * Institution} row for display, never for isolation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InstitutionService {

    private final InstitutionRepository institutionRepository;
    private final CollegeRepository collegeRepository;
    private final TenantContext tenantContext;

    @Transactional(readOnly = true)
    public InstitutionResponse getInstitution() {
        User user = tenantContext.currentUser();
        College college = user != null ? user.getCollege() : null;
        if (college == null) {
            College defaultCollege = collegeRepository.findByCode("DEV001").orElse(null);
            if (defaultCollege != null) {
                return toResponse(defaultCollege);
            }
            Institution inst = institutionRepository.findById(Institution.SINGLETON_ID).orElse(null);
            return toResponse(inst);
        }
        return toResponse(college);
    }

    @Transactional
    public InstitutionResponse updateInstitution(InstitutionRequest request) {
        User user = tenantContext.currentUser();
        College college = user != null ? user.getCollege() : null;
        if (college == null) {
            Institution inst = institutionRepository.findById(Institution.SINGLETON_ID)
                .orElseGet(() -> Institution.builder().id(Institution.SINGLETON_ID).build());
            inst.setName(request.getName());
            inst.setAddress(request.getAddress());
            Institution saved = institutionRepository.save(inst);
            log.info("Institution information updated by {} (legacy singleton row)",
                inst.getUpdatedBy() != null ? inst.getUpdatedBy() : "SUPER_ADMIN");
            return toResponse(saved);
        }

        college.setName(request.getName());
        college.setCode(request.getCode() != null && !request.getCode().isBlank()
            ? request.getCode() : college.getCode());
        college.setAddress(request.getAddress());
        college.setPhone(request.getPhone());
        college.setEmail(request.getEmail());
        College saved = collegeRepository.save(college);
        log.info("College '{}' information updated by user {}", saved.getName(),
            user.getUsername() != null ? user.getUsername() : user.getId());
        return toResponse(saved);
    }

    private InstitutionResponse toResponse(College c) {
        return InstitutionResponse.builder()
            .id(c.getId())
            .name(c.getName())
            .code(c.getCode())
            .address(c.getAddress())
            .phone(c.getPhone())
            .email(c.getEmail())
            .build();
    }

    private InstitutionResponse toResponse(Institution inst) {
        if (inst == null) {
            return InstitutionResponse.builder().id(Institution.SINGLETON_ID).name("Default Institution").build();
        }
        return InstitutionResponse.builder()
            .id(inst.getId())
            .name(inst.getName())
            .address(inst.getAddress())
            .build();
    }
}