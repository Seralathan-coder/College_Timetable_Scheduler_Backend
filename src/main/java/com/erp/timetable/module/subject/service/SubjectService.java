package com.erp.timetable.module.subject.service;

import com.erp.timetable.common.exception.BusinessException;
import com.erp.timetable.common.exception.ResourceNotFoundException;
import com.erp.timetable.common.response.PageResponse;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.AcademicYearRepository;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.erp.timetable.module.subject.dto.SubjectRequest;
import com.erp.timetable.module.subject.dto.SubjectResponse;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.config.security.TenantContext;
import com.erp.timetable.config.security.DepartmentScopeResolver;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubjectService {

    private final SubjectRepository subjectRepository;
    private final DepartmentRepository departmentRepository;
    private final AcademicYearRepository academicYearRepository;
    private final SectionRepository sectionRepository;
    private final FacultyRepository facultyRepository;
    private final TenantContext tenantContext;
    private final DepartmentScopeResolver departmentScopeResolver;
    private final com.erp.timetable.module.timetable.repository.TimetableEntryRepository entryRepository;

    @Transactional
    public SubjectResponse createSubject(SubjectRequest request) {
        Department department = request.getDepartmentId() != null ?
            departmentRepository.findById(request.getDepartmentId()).orElse(null) : null;

        AcademicYear academicYear = request.getAcademicYearId() != null ?
            academicYearRepository.findById(request.getAcademicYearId()).orElse(null) : null;

        Section section = request.getSectionId() != null ?
            sectionRepository.findById(request.getSectionId()).orElse(null) : null;

        Faculty assignedFaculty = request.getFacultyId() != null ?
            facultyRepository.findById(request.getFacultyId()).orElse(null) : null;

        // The subject's department/year/section/faculty all come from the body
        // and are not covered by the path-id guard (absent on create).
        assertScope(department, academicYear, section, assignedFaculty);
        validateHierarchy(department, academicYear, section);
        assertSubjectCodeAvailable(request.getSubjectCode(), department, academicYear, section, null);

        Subject subject = Subject.builder()
            .subjectCode(request.getSubjectCode())
            .subjectName(request.getSubjectName())
            .department(department)
            .academicYear(academicYear)
            .section(section)
            .assignedFaculty(assignedFaculty)
            .semester(request.getSemester())
            .credits(request.getCredits())
            .theoryHours(request.getTheoryHours())
            .practicalHours(request.getPracticalHours())
            .subjectType(request.getSubjectType() != null ? request.getSubjectType() : "THEORY")
            .sessionBlockSize(request.getSessionBlockSize() != null ? request.getSessionBlockSize() : 1)
            .isActive(request.getIsActive() != null ? request.getIsActive() : true)
            .totalSemesterHours(request.getTotalSemesterHours() != null ? request.getTotalSemesterHours() : 45)
            .teachingWeeks(request.getTeachingWeeks() != null ? request.getTeachingWeeks() : 15)
            .build();

        Subject saved = subjectRepository.save(subject);
        log.info("Subject created: {} - {}", saved.getSubjectCode(), saved.getSubjectName());
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<SubjectResponse> getSubjects(int page, int size, String search, Long deptId, Long academicYearId, Long sectionId, String subjectType, String sort) {
        Sort sortObj = Sort.by(Sort.Direction.ASC, "subjectCode");
        Pageable pageable = PageRequest.of(page, size, sortObj);

        User caller = tenantContext.currentUser();
        boolean global = caller == null || caller.hasRole(RoleName.ROLE_SUPER_ADMIN);
        String normalizedSearch = search != null && search.isBlank() ? null : search;
        String normalizedType = subjectType != null && subjectType.isBlank() ? null : subjectType;
        Long effectiveDeptId = departmentScopeResolver.effectiveFilterDepartmentId(deptId);

        Page<Subject> pageResult;
        if (global) {
            pageResult = subjectRepository.searchSubjects(
                normalizedSearch, effectiveDeptId, academicYearId, sectionId, normalizedType, pageable);
        } else {
            Long collegeId = caller.getCollege() != null ? caller.getCollege().getId() : null;
            pageResult = subjectRepository.searchSubjectsByCollege(
                normalizedSearch, effectiveDeptId, academicYearId, sectionId, normalizedType, collegeId, pageable);
        }

        List<SubjectResponse> content = pageResult.getContent().stream()
            .map(this::mapToResponse)
            .toList();

        return PageResponse.<SubjectResponse>builder()
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
    public SubjectResponse getSubjectById(Long id) {
        Subject subject = subjectRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Subject", "id", id));
        return mapToResponse(subject);
    }

    /**
     * Faculty self-scoped subject list: ONLY the subjects assigned to the
     * calling faculty member's profile, read-only. Identity comes from the
     * authenticated principal — no client-supplied id is ever read.
     */
    @Transactional(readOnly = true)
    public List<SubjectResponse> getMySubjects(UserPrincipal principal) {
        Faculty faculty = facultyRepository.findByUserId(principal.getId())
            .orElseThrow(() -> new BusinessException(
                "No faculty profile is linked to this account."));
        return subjectRepository.findByAssignedFacultyId(faculty.getId()).stream()
            .map(this::mapToResponse)
            .toList();
    }

    @Transactional
    public SubjectResponse updateSubject(Long id, SubjectRequest request) {
        Subject subject = subjectRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Subject", "id", id));

        Department department = request.getDepartmentId() != null ?
            departmentRepository.findById(request.getDepartmentId()).orElse(null) : null;

        AcademicYear academicYear = request.getAcademicYearId() != null ?
            academicYearRepository.findById(request.getAcademicYearId()).orElse(null) : null;

        Section section = request.getSectionId() != null ?
            sectionRepository.findById(request.getSectionId()).orElse(null) : null;

        Faculty assignedFaculty = request.getFacultyId() != null ?
            facultyRepository.findById(request.getFacultyId()).orElse(null) : null;

        assertUpdateScope(department, academicYear, section, assignedFaculty);
        validateHierarchy(department, academicYear, section);
        assertSubjectCodeAvailable(request.getSubjectCode(), department, academicYear, section, id);

        subject.setSubjectCode(request.getSubjectCode());
        subject.setSubjectName(request.getSubjectName());
        subject.setDepartment(department);
        subject.setAcademicYear(academicYear);
        subject.setSection(section);
        subject.setAssignedFaculty(assignedFaculty);
        subject.setSemester(request.getSemester());
        subject.setCredits(request.getCredits());
        subject.setTheoryHours(request.getTheoryHours());
        subject.setPracticalHours(request.getPracticalHours());
        subject.setSubjectType(request.getSubjectType() != null ? request.getSubjectType() : subject.getSubjectType());
        subject.setSessionBlockSize(request.getSessionBlockSize() != null ? request.getSessionBlockSize() : subject.getSessionBlockSize());
        subject.setIsActive(request.getIsActive() != null ? request.getIsActive() : subject.getIsActive());
        if (request.getTotalSemesterHours() != null) subject.setTotalSemesterHours(request.getTotalSemesterHours());
        if (request.getTeachingWeeks() != null) subject.setTeachingWeeks(request.getTeachingWeeks());

        Subject updated = subjectRepository.save(subject);
        return mapToResponse(updated);
    }

    @Transactional
    public void deleteSubject(Long id) {
        Subject subject = subjectRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Subject", "id", id));
        entryRepository.deleteBySubjectId(id);
        subjectRepository.delete(subject);
    }

    /**
     * Confines every department-scoped identifier in a create/update body to the
     * caller's own department. A no-op for callers that are not department
     * restricted; raises a {@link BusinessException} for an HOD targeting another
     * department (directly or through a year, section or faculty member).
     */
    private void assertScope(Department department, AcademicYear academicYear,
                             Section section, Faculty assignedFaculty) {
        departmentScopeResolver.assertDepartmentInScope(department);
        departmentScopeResolver.assertAcademicYearInScope(academicYear);
        departmentScopeResolver.assertSectionInScope(section);
        departmentScopeResolver.assertFacultyAssignable(department, assignedFaculty);
    }

    /**
     * Update-path variant of {@link #assertScope}: additionally refuses a
     * {@code null} department so a department-restricted HOD cannot detach the
     * subject from their own department by omitting the field. Callers that are
     * not department restricted are unaffected.
     */
    private void assertUpdateScope(Department department, AcademicYear academicYear,
                                   Section section, Faculty assignedFaculty) {
        departmentScopeResolver.assertNotDetached(department);
        departmentScopeResolver.assertAcademicYearInScope(academicYear);
        departmentScopeResolver.assertSectionInScope(section);
        departmentScopeResolver.assertFacultyAssignable(department, assignedFaculty);
    }

    /**
     * Business rule for subject-code reuse. Section is neither a plain unique key
     * nor ignored:
     * <ul>
     *   <li>a code may be repeated across DIFFERENT sections of the SAME academic
     *       year of a department (e.g. CS266 to sections A, B and C of 1st year);</li>
     *   <li>a code must map to exactly ONE academic year per department, so the same
     *       code in another year (2nd/3rd/4th) of that department is rejected;</li>
     *   <li>a different department is a separate code space; and</li>
     *   <li>the same section + year may not hold the same code twice.</li>
     * </ul>
     * This year dependency cannot be expressed as a single SQL unique constraint,
     * so it is enforced here; {@code excludeId} keeps the edited record from
     * conflicting with itself on the update path.
     */
    private void assertSubjectCodeAvailable(String subjectCode, Department department,
                                            AcademicYear academicYear, Section section, Long excludeId) {
        boolean otherYear = excludeId == null
            ? subjectRepository.existsByDepartment_IdAndSubjectCodeAndAcademicYear_IdNot(
                department.getId(), subjectCode, academicYear.getId())
            : subjectRepository.existsByDepartment_IdAndSubjectCodeAndAcademicYear_IdNotAndIdNot(
                department.getId(), subjectCode, academicYear.getId(), excludeId);
        if (otherYear) {
            throw new BusinessException("Subject code '" + subjectCode
                + "' is already used in another academic year of this department");
        }

        boolean sameOffering = excludeId == null
            ? subjectRepository.existsByDepartment_IdAndSubjectCodeAndAcademicYear_IdAndSection_Id(
                department.getId(), subjectCode, academicYear.getId(), section.getId())
            : subjectRepository.existsByDepartment_IdAndSubjectCodeAndAcademicYear_IdAndSection_IdAndIdNot(
                department.getId(), subjectCode, academicYear.getId(), section.getId(), excludeId);
        if (sameOffering) {
            throw new BusinessException("Subject with code '" + subjectCode
                + "' already exists for this section and academic year");
        }
    }

    /**
     * Subjects must belong to a fully-specified Department -> Academic Year ->
     * Section hierarchy and a valid semester. Every level of the chain is required,
     * and the section must belong to the selected academic year, which in turn must
     * belong to the selected department. Rows created before this hierarchy was
     * enforced (department-level subjects with null year/section) are left untouched.
     */
    private void validateHierarchy(Department department, AcademicYear academicYear, Section section) {
        if (department == null) {
            throw new BusinessException("Department is required for a subject. Select the department offering this subject.");
        }
        if (academicYear == null) {
            throw new BusinessException("Academic Year is required for a subject. Select the academic year for this subject.");
        }
        if (section == null) {
            throw new BusinessException("Section is required for a subject. Select the section this subject is taught to.");
        }
        if (academicYear.getDepartment() == null || !Objects.equals(academicYear.getDepartment().getId(), department.getId())) {
            throw new BusinessException("Academic year '" + academicYear.getYearLabel()
                + "' does not belong to department '" + department.getName() + "'. Choose a matching academic year.");
        }
        if (section.getAcademicYear() == null || !Objects.equals(section.getAcademicYear().getId(), academicYear.getId())) {
            throw new BusinessException("Section '" + section.getName()
                + "' does not belong to academic year '" + academicYear.getYearLabel() + "'. Choose a matching section.");
        }
        assertYearEnabled(academicYear, section);
    }

    /**
     * Subjects may not reference an academic year (or a section whose year) that
     * is disabled in the department's per-year configuration.
     */
    private void assertYearEnabled(AcademicYear academicYear, Section section) {
        if (academicYear != null && !Boolean.TRUE.equals(academicYear.getIsEnabled())) {
            throw new BusinessException("Cannot assign subject to '" + academicYear.getYearLabel()
                + "': the academic year is disabled for its department. Enable it in Department Management first.");
        }
        if (section != null && section.getAcademicYear() != null
                && !Boolean.TRUE.equals(section.getAcademicYear().getIsEnabled())) {
            throw new BusinessException("Cannot assign subject to section '" + section.getName()
                + "': its academic year '" + section.getAcademicYear().getYearLabel() + "' is disabled.");
        }
    }

    private SubjectResponse mapToResponse(Subject s) {
        return SubjectResponse.builder()
            .id(s.getId())
            .subjectCode(s.getSubjectCode())
            .subjectName(s.getSubjectName())
            .departmentId(s.getDepartment() != null ? s.getDepartment().getId() : null)
            .departmentName(s.getDepartment() != null ? s.getDepartment().getName() : null)
            .academicYearId(s.getAcademicYear() != null ? s.getAcademicYear().getId() : null)
            .yearLabel(s.getAcademicYear() != null ? s.getAcademicYear().getYearLabel() : null)
            .sectionId(s.getSection() != null ? s.getSection().getId() : null)
            .sectionName(s.getSection() != null ? s.getSection().getName() : null)
            .facultyId(s.getAssignedFaculty() != null ? s.getAssignedFaculty().getId() : null)
            .facultyName(s.getAssignedFaculty() != null ? s.getAssignedFaculty().getFullName() : null)
            .semester(s.getSemester())
            .credits(s.getCredits())
            .theoryHours(s.getTheoryHours())
            .practicalHours(s.getPracticalHours())
            .subjectType(s.getSubjectType())
            .sessionBlockSize(s.getSessionBlockSize() != null ? s.getSessionBlockSize() : 1)
            .isActive(s.getIsActive())
            .totalSemesterHours(s.getTotalSemesterHours())
            .teachingWeeks(s.getTeachingWeeks())
            .createdAt(s.getCreatedAt())
            .build();
    }
}
