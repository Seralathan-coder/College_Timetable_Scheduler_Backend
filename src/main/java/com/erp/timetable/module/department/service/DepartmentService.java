package com.erp.timetable.module.department.service;

import com.erp.timetable.common.exception.BusinessException;
import com.erp.timetable.common.exception.ResourceNotFoundException;
import com.erp.timetable.common.response.PageResponse;
import com.erp.timetable.module.department.dto.*;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.AcademicYearRepository;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
import com.erp.timetable.module.timetable.repository.TimetableEntryRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.auth.entity.Role;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.RoleRepository;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.config.security.TenantContext;
import com.erp.timetable.config.security.DepartmentScopeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class DepartmentService {

    /** Every department always contains these four academic years. */
    private static final List<String> STANDARD_YEARS = List.of("1st Year", "2nd Year", "3rd Year", "4th Year");

    /** The maximum possible sections each academic year may contain. */
    private static final List<String> AVAILABLE_SECTIONS = List.of("A", "B", "C", "D", "E");

    /** Sections enabled by default when the request does not specify them. */
    private static final List<String> DEFAULT_SECTIONS = List.of("A", "B");

    private final DepartmentRepository departmentRepository;
    private final AcademicYearRepository academicYearRepository;
    private final SectionRepository sectionRepository;
    private final SubjectRepository subjectRepository;
    private final TimetableRepository timetableRepository;
    private final TimetableEntryRepository timetableEntryRepository;
    private final ClassroomRepository classroomRepository;
    private final FacultyRepository facultyRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantContext tenantContext;
    private final DepartmentScopeResolver departmentScopeResolver;

    @Transactional
    public DepartmentResponse createDepartment(DepartmentRequest request) {
        User caller = tenantContext.currentUser();
        Long effectiveCollegeId = caller != null && caller.getCollege() != null
            ? caller.getCollege().getId() : null;
        if (departmentRepository.existsByNameForCollege(request.getName(), effectiveCollegeId)) {
            throw new BusinessException("Department with name '" + request.getName() + "' already exists in this college");
        }

        Department department = Department.builder()
            .name(request.getName())
            .hodName(request.getHodName())
            .contactEmail(request.getContactEmail())
            .contactPhone(request.getContactPhone())
            .building(request.getBuilding())
            .description(request.getDescription())
            .isArchived(false)
            .build();

        if (caller != null) {
            department.setCollege(caller.getCollege());
        }

        // Build the fixed four academic years with the sections selected per year
        Map<String, YearConfig> yearSections = normalizeYearSections(request);
        for (Map.Entry<String, YearConfig> entry : yearSections.entrySet()) {
            YearConfig config = entry.getValue();
            AcademicYear year = AcademicYear.builder()
                .yearLabel(entry.getKey())
                .isEnabled(config.enabled)
                .build();

            if (config.enabled) {
                for (String secName : config.sections) {
                    year.addSection(Section.builder()
                        .name(secName)
                        .studentStrength(60)
                        .status("ACTIVE")
                        .build());
                }
            }
            department.addAcademicYear(year);
        }

        Department saved = departmentRepository.save(department);
        createHodLogin(saved, request);
        log.info("Department created: {} with {} years and {} sections",
            saved.getName(), yearSections.size(),
            yearSections.values().stream().filter(c -> c.enabled).mapToInt(c -> c.sections.size()).sum());
        return mapToResponse(saved);
    }

    /**
     * Provisions the ROLE_HOD account with the credentials supplied on the
     * department creation form (if any). The account is permanently bound to
     * the created department. Never reuses/overwrites an existing account —
     * a conflicting Login ID or email aborts with a 422 before any write.
     */
    private void createHodLogin(Department saved, DepartmentRequest request) {
        if (request.getHodUsername() == null || request.getHodUsername().isBlank()) {
            return;
        }
        String username = request.getHodUsername().trim();
        Long collegeId = saved.getCollege() != null ? saved.getCollege().getId() : null;
        if (userRepository.existsByUsernameForCollege(username, collegeId)) {
            throw new BusinessException("HOD Login ID '" + username + "' is already in use in this college");
        }
        String password = request.getHodPassword();
        if (password == null || password.isBlank()) {
            throw new BusinessException("HOD Login Password is required when providing an HOD Login ID");
        }
        String email = request.getContactEmail();
        if (email == null || email.isBlank()) {
            email = username.toLowerCase() + "@college.edu";
        }
        if (userRepository.existsByEmailForCollege(email, collegeId)) {
            throw new BusinessException("HOD email '" + email + "' is already in use in this college");
        }

        Role hodRole = roleRepository.findByName(RoleName.ROLE_HOD)
            .orElseThrow(() -> new BusinessException("HOD role is not configured"));

        User hod = User.builder()
            .username(username)
            .email(email)
            .password(passwordEncoder.encode(password))
            .fullName(request.getHodName() != null && !request.getHodName().isBlank()
                ? request.getHodName() : "HOD of " + saved.getName())
            .department(saved)
            .college(saved.getCollege())
            .isActive(true)
            .build();
        hod.addRole(hodRole);
        userRepository.save(hod);
        log.info("HOD login created for department {}: username={}", saved.getName(), username);
    }

    @Transactional(readOnly = true)
    public PageResponse<DepartmentResponse> getDepartments(int page, int size, String search, Boolean isArchived, String sort) {
        Sort sortObj = Sort.by(Sort.Direction.ASC, "name");
        if (sort != null && sort.contains(",")) {
            String[] parts = sort.split(",");
            sortObj = Sort.by("desc".equalsIgnoreCase(parts[1]) ? Sort.Direction.DESC : Sort.Direction.ASC, parts[0]);
        }

        Pageable pageable = PageRequest.of(page, size, sortObj);
        User caller = tenantContext.currentUser();
        boolean global = caller == null || caller.hasRole(RoleName.ROLE_SUPER_ADMIN);

        Page<Department> pageResult;
        if (global) {
            pageResult = departmentRepository.searchDepartments(
                search != null && search.isBlank() ? null : search,
                isArchived,
                pageable
            );
        } else {
            Long collegeId = caller.getCollege() != null ? caller.getCollege().getId() : null;
            // An HOD lists their own department only; the college still scopes
            // everyone else.
            Long effectiveDeptId = departmentScopeResolver.effectiveFilterDepartmentId(null);
            pageResult = effectiveDeptId != null
                ? departmentRepository.searchDepartmentsByCollegeAndDepartment(
                    search != null && search.isBlank() ? null : search,
                    isArchived,
                    collegeId,
                    effectiveDeptId,
                    pageable)
                : departmentRepository.searchDepartmentsByCollege(
                    search != null && search.isBlank() ? null : search,
                    isArchived,
                    collegeId,
                    pageable
                );
        }

        List<DepartmentResponse> content = pageResult.getContent().stream()
            .map(this::mapToResponse)
            .toList();

        return PageResponse.<DepartmentResponse>builder()
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
    public DepartmentResponse getDepartmentById(Long id) {
        Department department = departmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));
        return mapToResponse(department);
    }

    @Transactional
    public DepartmentResponse updateDepartment(Long id, DepartmentRequest request) {
        Department department = departmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));

        Long effectiveCollegeId = department.getCollege() != null ? department.getCollege().getId() : null;
        if (departmentRepository.existsByNameAndIdNotForCollege(request.getName(), id, effectiveCollegeId)) {
            throw new BusinessException("Department with name '" + request.getName() + "' already exists in this college");
        }

        department.setName(request.getName());
        department.setHodName(request.getHodName());
        department.setContactEmail(request.getContactEmail());
        department.setContactPhone(request.getContactPhone());
        department.setBuilding(request.getBuilding());
        department.setDescription(request.getDescription());

        Map<String, YearConfig> yearSections = normalizeYearSections(request);

        // 1. Remove academic years that are not part of the fixed four (defensive)
        department.getAcademicYears().removeIf(y -> !STANDARD_YEARS.contains(y.getYearLabel()));

        // 2. Collect the sections being deselected so their timetables/subjects
        //    can be cleaned up before the rows are orphan-removed.
        //    Disabled years count as deselected for every section they have.
        List<Section> removedSections = new ArrayList<>();
        for (AcademicYear year : department.getAcademicYears()) {
            YearConfig config = yearSections.get(year.getYearLabel());
            List<String> target = (config != null && config.enabled) ? config.sections : List.of();
            year.getSections().stream()
                .filter(s -> !target.contains(s.getName()))
                .forEach(removedSections::add);
        }

        // 3. Delete timetables of deselected sections and detach their subjects,
        //    otherwise FK constraints break when the section rows are deleted.
        List<Long> removedSectionIds = removedSections.stream()
            .map(Section::getId).filter(Objects::nonNull).distinct().toList();
        if (!removedSectionIds.isEmpty()) {
            List<Timetable> removedTimetables = timetableRepository.findBySectionIdIn(removedSectionIds);
            if (!removedTimetables.isEmpty()) {
                timetableRepository.deleteAll(removedTimetables);
                timetableRepository.flush();
            }
            List<Subject> linkedSubjects = subjectRepository.findBySectionIdIn(removedSectionIds);
            for (Subject subject : linkedSubjects) {
                subject.setSection(null);
                subjectRepository.save(subject);
            }
        }

        for (AcademicYear year : department.getAcademicYears()) {
            YearConfig config = yearSections.get(year.getYearLabel());
            List<String> target = (config != null && config.enabled) ? config.sections : List.of();
            year.getSections().removeIf(s -> !target.contains(s.getName()));
        }

        // 4. Update or create the four academic years with their selected sections
        for (Map.Entry<String, YearConfig> entry : yearSections.entrySet()) {
            String yearLabel = entry.getKey();
            YearConfig config = entry.getValue();
            List<String> targetSections = config.enabled ? config.sections : List.of();

            AcademicYear year = department.getAcademicYears().stream()
                .filter(y -> y.getYearLabel().equals(yearLabel))
                .findFirst()
                .orElse(null);

            if (year == null) {
                year = AcademicYear.builder()
                    .yearLabel(yearLabel)
                    .isEnabled(config.enabled)
                    .build();
                for (String secName : targetSections) {
                    year.addSection(Section.builder()
                        .name(secName)
                        .studentStrength(60)
                        .status("ACTIVE")
                        .build());
                }
                department.addAcademicYear(year);
            } else {
                year.setIsEnabled(config.enabled);
                List<String> existingSecNames = year.getSections().stream().map(Section::getName).toList();
                for (String secName : targetSections) {
                    if (!existingSecNames.contains(secName)) {
                        year.addSection(Section.builder()
                            .name(secName)
                            .studentStrength(60)
                            .status("ACTIVE")
                            .build());
                    }
                }
            }
        }

        Department updated = departmentRepository.save(department);
        log.info("Department updated: {} with {} years and {} sections", updated.getId(), yearSections.size(),
            yearSections.values().stream().filter(c -> c.enabled).mapToInt(c -> c.sections.size()).sum());
        return mapToResponse(updated);
    }

    /**
     * Normalizes the request's per-year section selection into a map of
     * {@code yearLabel -> YearConfig} for exactly the four standard years.
     * <p>
     * A year is <em>enabled</em> unless the request explicitly sets
     * {@code enabled = false}. Disabled years keep no selectable sections.
     * Sections are uppercased, deduplicated and validated against A–E; years
     * missing from the request fall back to the default sections.
     */
    private Map<String, YearConfig> normalizeYearSections(DepartmentRequest request) {
        Map<String, YearSectionsRequest> requested = new HashMap<>();
        if (request.getYears() != null) {
            for (YearSectionsRequest yearRequest : request.getYears()) {
                if (yearRequest.getYearLabel() == null) {
                    continue;
                }
                requested.put(yearRequest.getYearLabel().trim(), yearRequest);
            }
        }

        Map<String, YearConfig> result = new LinkedHashMap<>();
        for (String yearLabel : STANDARD_YEARS) {
            YearSectionsRequest yearRequest = requested.get(yearLabel);
            if (yearRequest == null) {
                result.put(yearLabel, new YearConfig(true, new ArrayList<>(DEFAULT_SECTIONS)));
                continue;
            }

            boolean enabled = !Boolean.FALSE.equals(yearRequest.getEnabled());
            List<String> normalized = new ArrayList<>();
            if (enabled && yearRequest.getSections() != null) {
                for (String section : yearRequest.getSections()) {
                    if (section == null) {
                        continue;
                    }
                    String name = section.trim().toUpperCase();
                    if (!AVAILABLE_SECTIONS.contains(name)) {
                        throw new BusinessException(
                            "Invalid section '" + section.trim() + "' for " + yearLabel
                                + ". Allowed sections are A, B, C, D and E.");
                    }
                    if (!normalized.contains(name)) {
                        normalized.add(name);
                    }
                }
            }
            result.put(yearLabel, new YearConfig(enabled, normalized));
        }
        return result;
    }

    /** Holds the enabled flag and selected sections for one academic year. */
    private static final class YearConfig {
        private final boolean enabled;
        private final List<String> sections;

        YearConfig(boolean enabled, List<String> sections) {
            this.enabled = enabled;
            this.sections = sections;
        }
    }

    @Transactional
    public void archiveDepartment(Long id) {
        Department department = departmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));
        department.setIsArchived(true);
        departmentRepository.save(department);
        log.info("Department archived: {}", id);
    }

    @Transactional
    public void restoreDepartment(Long id) {
        Department department = departmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));
        department.setIsArchived(false);
        departmentRepository.save(department);
        log.info("Department restored: {}", id);
    }

    @Transactional
    public void deleteDepartment(Long id) {
        Department department = departmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));

        // 1. Delete timetables — cascade-deletes entries and conflicts
        List<com.erp.timetable.module.timetable.entity.Timetable> timetables =
            timetableRepository.findByDepartmentId(id);
        if (!timetables.isEmpty()) {
            timetableRepository.deleteAll(timetables);
            timetableRepository.flush();
        }

        // 2. Delete subjects — they belong to this department's academic years/sections
        List<Subject> subjects = subjectRepository.findByDepartmentId(id);
        if (!subjects.isEmpty()) {
            subjectRepository.deleteAll(subjects);
            subjectRepository.flush();
        }

        // 3. Unlink faculty — keep the records, remove department association
        List<Faculty> faculties = facultyRepository.findByDepartmentId(id);
        for (Faculty f : faculties) {
            f.setDepartment(null);
        }
        if (!faculties.isEmpty()) {
            facultyRepository.saveAll(faculties);
            facultyRepository.flush();
        }

        // 4. Unlink classrooms — keep the rooms, remove department association
        List<Classroom> classrooms = classroomRepository.findByDepartment_Id(id);
        for (Classroom c : classrooms) {
            c.setDepartment(null);
        }
        if (!classrooms.isEmpty()) {
            classroomRepository.saveAll(classrooms);
            classroomRepository.flush();
        }

        // 5. Unlink users — remove department association, never delete user accounts
        List<User> users = userRepository.findByDepartment_Id(id);
        for (User u : users) {
            u.setDepartment(null);
        }
        if (!users.isEmpty()) {
            userRepository.saveAll(users);
            userRepository.flush();
        }

        // 6. Delete department — cascades to AcademicYear → Section
        departmentRepository.delete(department);
        log.info("Department {} deleted: {} ({} timetables removed, {} subjects removed, {} faculty unlinked, {} classrooms unlinked, {} users unlinked)",
            id, department.getName(),
            timetables.size(), subjects.size(), faculties.size(), classrooms.size(), users.size());
    }

    /**
     * Returns dependency counts for a department so the frontend can show
     * an informed confirmation dialog before hard-deleting.
     */
    public Map<String, Integer> getDependencyCounts(Long id) {
        if (!departmentRepository.existsById(id)) {
            throw new ResourceNotFoundException("Department", "id", id);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("timetables", timetableRepository.findByDepartmentId(id).size());
        counts.put("subjects", subjectRepository.findByDepartmentId(id).size());
        counts.put("faculty", facultyRepository.findByDepartmentId(id).size());
        counts.put("classrooms", classroomRepository.findByDepartment_Id(id).size());
        counts.put("users", userRepository.findByDepartment_Id(id).size());
        return counts;
    }

    private DepartmentResponse mapToResponse(Department d) {
        List<AcademicYearDto> yearDtos = d.getAcademicYears() == null ? List.of() :
            d.getAcademicYears().stream().map(y -> AcademicYearDto.builder()
                .id(y.getId())
                .departmentId(d.getId())
                .yearLabel(y.getYearLabel())
                .isEnabled(y.getIsEnabled())
                .sections(y.getSections() == null ? List.of() :
                    y.getSections().stream().map(s -> SectionDto.builder()
                        .id(s.getId())
                        .academicYearId(y.getId())
                        .name(s.getName())
                        .studentStrength(s.getStudentStrength())
                        .facultyAdvisorId(s.getFacultyAdvisorId())
                        .status(s.getStatus())
                        .build()).toList()
                )
                .build()
            )
            .sorted(Comparator.comparingInt(y -> {
                int idx = STANDARD_YEARS.indexOf(y.getYearLabel());
                return idx < 0 ? Integer.MAX_VALUE : idx;
            }))
            .toList();

        return DepartmentResponse.builder()
            .id(d.getId())
            .name(d.getName())
            .hodName(d.getHodName())
            .contactEmail(d.getContactEmail())
            .contactPhone(d.getContactPhone())
            .building(d.getBuilding())
            .description(d.getDescription())
            .isArchived(d.getIsArchived())
            .collegeId(d.getCollege() != null ? d.getCollege().getId() : null)
            .createdAt(d.getCreatedAt())
            .updatedAt(d.getUpdatedAt())
            .createdBy(d.getCreatedBy())
            .academicYears(yearDtos)
            .build();
    }
}
