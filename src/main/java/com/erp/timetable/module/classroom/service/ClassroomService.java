package com.erp.timetable.module.classroom.service;

import com.erp.timetable.common.exception.ResourceNotFoundException;
import com.erp.timetable.common.response.PageResponse;
import com.erp.timetable.module.classroom.dto.ClassroomRequest;
import com.erp.timetable.module.classroom.dto.ClassroomResponse;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.AcademicYearRepository;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.erp.timetable.module.timetable.repository.TimetableEntryRepository;
import com.erp.timetable.config.security.TenantContext;
import com.erp.timetable.config.security.DepartmentScopeResolver;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClassroomService {

    private final ClassroomRepository classroomRepository;
    private final DepartmentRepository departmentRepository;
    private final AcademicYearRepository academicYearRepository;
    private final SectionRepository sectionRepository;
    private final TimetableEntryRepository timetableEntryRepository;
    private final TenantContext tenantContext;
    private final DepartmentScopeResolver departmentScopeResolver;

    @Transactional
    public ClassroomResponse createClassroom(ClassroomRequest request) {
        Department department = null;
        if (request.getDepartmentId() != null) {
            department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", request.getDepartmentId()));
        }

        AcademicYear academicYear = null;
        if (request.getAcademicYearId() != null) {
            academicYear = academicYearRepository.findById(request.getAcademicYearId())
                .orElseThrow(() -> new ResourceNotFoundException("AcademicYear", "id", request.getAcademicYearId()));
        }

        Section section = null;
        if (request.getSectionId() != null) {
            section = sectionRepository.findById(request.getSectionId())
                .orElseThrow(() -> new ResourceNotFoundException("Section", "id", request.getSectionId()));
        }

        // Department, academic year and section all arrive in the body and are
        // not covered by the path-id guard (absent on create).
        assertScope(department, academicYear, section);

        Classroom classroom = Classroom.builder()
            .roomNumber(request.getRoomNumber())
            .roomName(request.getRoomName())
            .building(request.getBuilding())
            .department(department)
            .academicYear(academicYear)
            .section(section)
            .roomType(request.getRoomType() != null ? request.getRoomType() : "LECTURE_HALL")
            .capacity(request.getCapacity())
            .floor(request.getFloor())
            .status(request.getStatus() != null ? request.getStatus() : "AVAILABLE")
            .build();

        Classroom saved = classroomRepository.save(classroom);
        log.info("Classroom created: {} in {}", saved.getRoomNumber(), saved.getBuilding());
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<ClassroomResponse> getClassrooms(int page, int size, String search, String roomType, String status, String sort) {
        Sort sortObj = Sort.by(Sort.Direction.ASC, "roomNumber");
        Pageable pageable = PageRequest.of(page, size, sortObj);

        User caller = tenantContext.currentUser();
        boolean global = caller == null || caller.hasRole(RoleName.ROLE_SUPER_ADMIN);
        String normalizedSearch = search != null && search.isBlank() ? null : search;
        String normalizedType = roomType != null && roomType.isBlank() ? null : roomType;
        String normalizedStatus = status != null && status.isBlank() ? null : status;
        // Previously the department slot was hardcoded to null here, so an HOD
        // always received every classroom in the college with no way to narrow.
        Long effectiveDeptId = departmentScopeResolver.effectiveFilterDepartmentId(null);

        Page<Classroom> pageResult;
        if (global) {
            pageResult = classroomRepository.searchClassrooms(
                normalizedSearch, normalizedType, normalizedStatus, pageable);
        } else {
            Long collegeId = caller.getCollege() != null ? caller.getCollege().getId() : null;
            pageResult = classroomRepository.searchClassroomsByCollege(
                normalizedSearch, effectiveDeptId, collegeId, normalizedType, normalizedStatus, pageable);
        }

        List<ClassroomResponse> content = pageResult.getContent().stream()
            .map(this::mapToResponse)
            .toList();

        return PageResponse.<ClassroomResponse>builder()
            .content(content)
            .page(pageResult.getNumber())
            .size(pageResult.getSize())
            .totalElements(pageResult.getTotalElements())
            .totalPages(pageResult.getTotalPages())
            .first(pageResult.isFirst())
            .last(pageResult.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public ClassroomResponse getClassroomById(Long id) {
        Classroom classroom = classroomRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Classroom", "id", id));
        return mapToResponse(classroom);
    }

    @Transactional
    public ClassroomResponse updateClassroom(Long id, ClassroomRequest request) {
        Classroom classroom = classroomRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Classroom", "id", id));

        Department department = null;
        if (request.getDepartmentId() != null) {
            department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", request.getDepartmentId()));
        }

        AcademicYear academicYear = null;
        if (request.getAcademicYearId() != null) {
            academicYear = academicYearRepository.findById(request.getAcademicYearId())
                .orElseThrow(() -> new ResourceNotFoundException("AcademicYear", "id", request.getAcademicYearId()));
        }

        Section section = null;
        if (request.getSectionId() != null) {
            section = sectionRepository.findById(request.getSectionId())
                .orElseThrow(() -> new ResourceNotFoundException("Section", "id", request.getSectionId()));
        }

        // Without this an HOD passes canManageClassroom() on their OWN classroom
        // and then moves it into another department's year/section.
        assertUpdateScope(department, academicYear, section);

        classroom.setRoomNumber(request.getRoomNumber());
        classroom.setRoomName(request.getRoomName());
        classroom.setBuilding(request.getBuilding());
        classroom.setDepartment(department);
        classroom.setAcademicYear(academicYear);
        classroom.setSection(section);
        classroom.setRoomType(request.getRoomType() != null ? request.getRoomType() : "LECTURE_HALL");
        classroom.setCapacity(request.getCapacity());
        classroom.setFloor(request.getFloor());
        classroom.setStatus(request.getStatus() != null ? request.getStatus() : "AVAILABLE");

        Classroom updated = classroomRepository.save(classroom);
        return mapToResponse(updated);
    }

    @Transactional
    public void deleteClassroom(Long id) {
        Classroom classroom = classroomRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Classroom", "id", id));

        // Remove timetable entries referencing this classroom
        // (timetable_entries.classroom_id is NOT NULL, so the entries must be
        //  deleted before the classroom row itself can be removed)
        timetableEntryRepository.deleteByClassroomId(id);

        classroomRepository.delete(classroom);
    }

    /**
     * Confines every department-scoped identifier in a create/update body to the
     * caller's own department. A no-op for callers that are not department
     * restricted; raises a {@link com.erp.timetable.common.exception.BusinessException}
     * for an HOD targeting another department directly or via a year/section.
     */
    private void assertScope(Department department, AcademicYear academicYear, Section section) {
        departmentScopeResolver.assertDepartmentInScope(department);
        departmentScopeResolver.assertAcademicYearInScope(academicYear);
        departmentScopeResolver.assertSectionInScope(section);
    }

    /**
     * Update-path variant of {@link #assertScope}: additionally refuses a
     * {@code null} department so a department-restricted HOD cannot detach the
     * classroom from their own department by omitting the field. Callers that
     * are not department restricted are unaffected.
     */
    private void assertUpdateScope(Department department, AcademicYear academicYear, Section section) {
        departmentScopeResolver.assertNotDetached(department);
        departmentScopeResolver.assertAcademicYearInScope(academicYear);
        departmentScopeResolver.assertSectionInScope(section);
    }

    private ClassroomResponse mapToResponse(Classroom c) {
        return ClassroomResponse.builder()
            .id(c.getId())
            .roomNumber(c.getRoomNumber())
            .roomName(c.getRoomName())
            .building(c.getBuilding())
            .departmentId(c.getDepartment() != null ? c.getDepartment().getId() : null)
            .departmentName(c.getDepartment() != null ? c.getDepartment().getName() : null)
            .academicYearId(c.getAcademicYear() != null ? c.getAcademicYear().getId() : null)
            .academicYearLabel(c.getAcademicYear() != null ? c.getAcademicYear().getYearLabel() : null)
            .sectionId(c.getSection() != null ? c.getSection().getId() : null)
            .sectionName(c.getSection() != null ? c.getSection().getName() : null)
            .roomType(c.getRoomType())
            .capacity(c.getCapacity())
            .floor(c.getFloor())
            .status(c.getStatus())
            .createdAt(c.getCreatedAt())
            .build();
    }
}
