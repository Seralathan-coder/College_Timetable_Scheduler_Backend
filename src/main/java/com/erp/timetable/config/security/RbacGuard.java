package com.erp.timetable.config.security;

import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.auth.security.UserPrincipal;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import com.erp.timetable.module.timetable.repository.TimetableEntryRepository;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SpEL target for {@code @PreAuthorize} — resolves ownership-scoped AND
 * college-scoped decisions from the runtime {@link Authentication} (never from
 * client-supplied data). Reads and writes are both college-scoped.
 *
 * <p>Semantics:
 * <ul>
 *   <li>SUPER_ADMIN — global platform access everywhere (all colleges).</li>
 *   <li>COLLEGE_ADMIN — everything inside their OWN college only.</li>
 *   <li>HOD — writes AND reads within their own department of their own collage.</li>
 *   <li>EXAM_COORDINATOR — timetable generation/regeneration across departments
 *       OF THEIR OWN COLLEGE only.</li>
 *   <li>FACULTY — self-scoped: availability and reports for their own record
 *       only; read access limited to their own college.</li>
 *   <li>STUDENT — no management access; login is disabled (see AuthService).</li>
 * </ul>
 *
 * <p>College identity always comes from the DB-loaded {@link User} bound to the
 * principal — never from the JWT or any request field. Legacy rows with no
 * college yet (null == null) are treated as belonging to the same (default)
 * tenant during the migration window; mixed null/non-null never matches.
 *
 * <p>These checks run at method level (the request has already passed the
 * "authenticated" filter in {@link com.erp.timetable.config.SecurityConfig}).
 */
@Component("rbacGuard")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RbacGuard {

    private static final RoleName[] STAFF_FALLBACK = {
        RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN, RoleName.ROLE_HOD,
        RoleName.ROLE_FACULTY, RoleName.ROLE_EXAM_COORDINATOR
    };

    private final UserRepository userRepository;
    private final FacultyRepository facultyRepository;
    private final SubjectRepository subjectRepository;
    private final ClassroomRepository classroomRepository;
    private final TimetableRepository timetableRepository;
    private final TimetableEntryRepository timetableEntryRepository;
    private final DepartmentRepository departmentRepository;
    private final SectionRepository sectionRepository;

    // ── Management writes (college-scoped) ───────────────────────────────

    /** SUPER_ADMIN: any; COLLEGE_ADMIN: any dept of own college; HOD: own dept of own college. */
    public boolean canManageDepartment(Authentication authentication, Long departmentId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN, RoleName.ROLE_HOD);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (departmentId == null) {
            return false;
        }
        Department dept = departmentRepository.findById(departmentId).orElse(null);
        if (dept == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_COLLEGE_ADMIN)) {
            return sameCollege(user, collegeIdOf(dept));
        }
        return hasRole(user, RoleName.ROLE_HOD)
            && sameDepartment(user, departmentId)
            && sameCollege(user, collegeIdOf(dept));
    }

    /**
     * Editing a department's own master data (name, contact, year/section
     * configuration, archive/restore) is a college-administration action.
     * SUPER_ADMIN: any; COLLEGE_ADMIN: own college only; HOD excluded — an HOD
     * gets a strictly read-only view of their own department, enforced here so
     * the UI cannot be bypassed.
     */
    public boolean canEditDepartment(Authentication authentication, Long departmentId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (departmentId == null || !hasRole(user, RoleName.ROLE_COLLEGE_ADMIN)) {
            return false;
        }
        Department dept = departmentRepository.findById(departmentId).orElse(null);
        if (dept == null) {
            return true;
        }
        return sameCollege(user, collegeIdOf(dept));
    }

    /** SUPER_ADMIN: any; COLLEGE_ADMIN: own college only; HOD excluded (destructive delete). */
    public boolean canDeleteDepartment(Authentication authentication, Long departmentId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (departmentId == null || !hasRole(user, RoleName.ROLE_COLLEGE_ADMIN)) {
            return false;
        }
        Department dept = departmentRepository.findById(departmentId).orElse(null);
        if (dept == null) {
            return true;
        }
        return sameCollege(user, collegeIdOf(dept));
    }

    /**
     * Creating a faculty record is a college-administration action:
     * SUPER_ADMIN: any; COLLEGE_ADMIN: own college only; HOD: excluded.
     *
     * <p>An HOD is confined to their own department and must not add people to
     * it, which is why this is separate from {@link #canManageFaculty} - the
     * latter still grants an HOD read/update on their OWN department's existing
     * faculty records. An account that also holds ROLE_COLLEGE_ADMIN or
     * ROLE_SUPER_ADMIN keeps full college administration, matching the
     * frontend's {@code isHodReadOnly} rule.
     */
    public boolean canCreateFaculty(Authentication authentication, Long departmentId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                    RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (departmentId == null || !hasRole(user, RoleName.ROLE_COLLEGE_ADMIN)) {
            return false;
        }
        Department dept = departmentRepository.findById(departmentId).orElse(null);
        if (dept == null) {
            return true;
        }
        return sameCollege(user, collegeIdOf(dept));
    }

    /** SUPER_ADMIN: any; COLLEGE_ADMIN: any faculty of own college; HOD: own dept of own college. */
    public boolean canManageFaculty(Authentication authentication, Long facultyId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN, RoleName.ROLE_HOD);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (facultyId == null) {
            return false;
        }
        Faculty faculty = facultyRepository.findById(facultyId).orElse(null);
        if (faculty == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_COLLEGE_ADMIN)) {
            return sameCollege(user, collegeIdOf(faculty));
        }
        return hasRole(user, RoleName.ROLE_HOD)
            && faculty.getDepartment() != null
            && sameDepartment(user, faculty.getDepartment().getId())
            && sameCollege(user, collegeIdOf(faculty));
    }

    /** SUPER_ADMIN: any; COLLEGE_ADMIN: own college; HOD: own dept of own college. */
    public boolean canManageSubject(Authentication authentication, Long subjectId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN, RoleName.ROLE_HOD);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (subjectId == null) {
            return false;
        }
        Subject subject = subjectRepository.findById(subjectId).orElse(null);
        if (subject == null || subject.getDepartment() == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_COLLEGE_ADMIN)) {
            return sameCollege(user, collegeIdOf(subject.getDepartment()));
        }
        return hasRole(user, RoleName.ROLE_HOD)
            && sameDepartment(user, subject.getDepartment().getId())
            && sameCollege(user, collegeIdOf(subject.getDepartment()));
    }

    /** SUPER_ADMIN: any; COLLEGE_ADMIN: own college; HOD: own dept of own college. */
    public boolean canManageClassroom(Authentication authentication, Long classroomId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN, RoleName.ROLE_HOD);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (classroomId == null) {
            return false;
        }
        Classroom classroom = classroomRepository.findById(classroomId).orElse(null);
        if (classroom == null || classroom.getDepartment() == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_COLLEGE_ADMIN)) {
            return sameCollege(user, collegeIdOf(classroom.getDepartment()));
        }
        return hasRole(user, RoleName.ROLE_HOD)
            && sameDepartment(user, classroom.getDepartment().getId())
            && sameCollege(user, collegeIdOf(classroom.getDepartment()));
    }

    // ── Availability ─────────────────────────────────────────────────────

    /**
     * SUPER_ADMIN — any faculty; COLLEGE_ADMIN — any faculty of own college;
     * HOD — own dept of own college; FACULTY — strictly their own record.
     */
    public boolean canSaveAvailability(Authentication authentication, Long facultyId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN, RoleName.ROLE_HOD, RoleName.ROLE_FACULTY);
        }
        if (facultyId == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        Faculty faculty = facultyRepository.findById(facultyId).orElse(null);
        if (faculty == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_COLLEGE_ADMIN)) {
            return sameCollege(user, collegeIdOf(faculty));
        }
        if (hasRole(user, RoleName.ROLE_HOD)) {
            return faculty.getDepartment() != null
                && sameDepartment(user, faculty.getDepartment().getId())
                && sameCollege(user, collegeIdOf(faculty));
        }
        // Availability is management data — even the faculty's own matrix is
        // out of scope for the Faculty role (set by HOD/admins during planning).
        if (hasRole(user, RoleName.ROLE_FACULTY)) {
            return false;
        }
        return false;
    }

    // ── Timetable generation / management ───────────────────────────────

    /** SUPER_ADMIN: any; COLLEGE_ADMIN / EXAM_COORDINATOR: own college; HOD: own dept of own college. */
    public boolean canGenerateTimetable(Authentication authentication, Long departmentId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN, RoleName.ROLE_EXAM_COORDINATOR, RoleName.ROLE_HOD);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (departmentId == null) {
            return false;
        }
        Department dept = departmentRepository.findById(departmentId).orElse(null);
        if (dept == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_COLLEGE_ADMIN) || hasRole(user, RoleName.ROLE_EXAM_COORDINATOR)) {
            return sameCollege(user, collegeIdOf(dept));
        }
        return canManageDepartment(authentication, departmentId);
    }

    /** SUPER_ADMIN: any; COLLEGE_ADMIN / EXAM_COORDINATOR: own college; HOD: own dept of own college. */
    public boolean canManageTimetable(Authentication authentication, Long timetableId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN, RoleName.ROLE_EXAM_COORDINATOR, RoleName.ROLE_HOD);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (timetableId == null) {
            return false;
        }
        Timetable timetable = timetableRepository.findById(timetableId).orElse(null);
        if (timetable == null || timetable.getDepartment() == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_COLLEGE_ADMIN) || hasRole(user, RoleName.ROLE_EXAM_COORDINATOR)) {
            return sameCollege(user, collegeIdOf(timetable.getDepartment()));
        }
        return canManageDepartment(authentication, timetable.getDepartment().getId());
    }

    public boolean canManageTimetableEntry(Authentication authentication, Long entryId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN, RoleName.ROLE_EXAM_COORDINATOR, RoleName.ROLE_HOD);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (entryId == null) {
            return false;
        }
        TimetableEntry entry = timetableEntryRepository.findById(entryId).orElse(null);
        if (entry == null || entry.getTimetable() == null || entry.getTimetable().getDepartment() == null) {
            return false;
        }
        Department dept = entry.getTimetable().getDepartment();
        if (hasRole(user, RoleName.ROLE_COLLEGE_ADMIN) || hasRole(user, RoleName.ROLE_EXAM_COORDINATOR)) {
            return sameCollege(user, collegeIdOf(dept));
        }
        return canManageDepartment(authentication, dept.getId());
    }

    // ── Deletes (SUPER_ADMIN or own-college COLLEGE_ADMIN) ───────────────

    public boolean canDeleteFaculty(Authentication authentication, Long facultyId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication, RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        return hasRole(user, RoleName.ROLE_COLLEGE_ADMIN) && canManageFaculty(authentication, facultyId);
    }

    public boolean canDeleteSubject(Authentication authentication, Long subjectId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication, RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        return hasRole(user, RoleName.ROLE_COLLEGE_ADMIN) && canManageSubject(authentication, subjectId);
    }

    public boolean canDeleteClassroom(Authentication authentication, Long classroomId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication, RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        return hasRole(user, RoleName.ROLE_COLLEGE_ADMIN) && canManageClassroom(authentication, classroomId);
    }

    public boolean canDeleteTimetable(Authentication authentication, Long timetableId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication, RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        return hasRole(user, RoleName.ROLE_COLLEGE_ADMIN) && canManageTimetable(authentication, timetableId);
    }

    // ── Institution / college self-service ───────────────────────────────

    /** Editing the own-college institution row: SUPER_ADMIN or any COLLEGE_ADMIN. */
    public boolean canManageInstitution(Authentication authentication) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN);
        }
        return hasRole(user, RoleName.ROLE_SUPER_ADMIN) || hasRole(user, RoleName.ROLE_COLLEGE_ADMIN);
    }

    // ── Reports ──────────────────────────────────────────────────────────

    /**
     * SUPER_ADMIN — any; COLLEGE_ADMIN / EXAM_COORDINATOR — own college;
     * HOD — own dept of own college; FACULTY — strictly their own report.
     */
    public boolean canViewFacultyReport(Authentication authentication, Long facultyId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication,
                RoleName.ROLE_SUPER_ADMIN, RoleName.ROLE_COLLEGE_ADMIN, RoleName.ROLE_HOD, RoleName.ROLE_EXAM_COORDINATOR);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (facultyId == null) {
            return false;
        }
        Faculty faculty = facultyRepository.findById(facultyId).orElse(null);
        if (faculty == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_COLLEGE_ADMIN) || hasRole(user, RoleName.ROLE_EXAM_COORDINATOR)) {
            return sameCollege(user, collegeIdOf(faculty));
        }
        if (hasRole(user, RoleName.ROLE_HOD)) {
            return faculty.getDepartment() != null
                && sameDepartment(user, faculty.getDepartment().getId())
                && sameCollege(user, collegeIdOf(faculty));
        }
        // Reports are management data — the Faculty role sees their schedule
        // through /timetable/my, never through the report centre.
        return false;
    }

    // ── Reads (college-scoped) ───────────────────────────────────────────

    public boolean canViewDepartment(Authentication authentication, Long departmentId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication, STAFF_FALLBACK);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (hasRole(user, RoleName.ROLE_FACULTY)) {
            return false;
        }
        if (departmentId == null) {
            return false;
        }
        Department dept = departmentRepository.findById(departmentId).orElse(null);
        if (dept == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_HOD)) {
            return sameDepartment(user, departmentId) && sameCollege(user, collegeIdOf(dept));
        }
        return sameCollege(user, collegeIdOf(dept));
    }

    public boolean canViewFaculty(Authentication authentication, Long facultyId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication, STAFF_FALLBACK);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (hasRole(user, RoleName.ROLE_FACULTY)) {
            return false;
        }
        if (facultyId == null) {
            return false;
        }
        Faculty faculty = facultyRepository.findById(facultyId).orElse(null);
        if (faculty == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_HOD)) {
            return faculty.getDepartment() != null
                && sameDepartment(user, faculty.getDepartment().getId())
                && sameCollege(user, collegeIdOf(faculty));
        }
        return sameCollege(user, collegeIdOf(faculty));
    }

    public boolean canViewSubject(Authentication authentication, Long subjectId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication, STAFF_FALLBACK);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (hasRole(user, RoleName.ROLE_FACULTY)) {
            return false;
        }
        if (subjectId == null) {
            return false;
        }
        Subject subject = subjectRepository.findById(subjectId).orElse(null);
        if (subject == null || subject.getDepartment() == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_HOD)) {
            return sameDepartment(user, subject.getDepartment().getId())
                && sameCollege(user, collegeIdOf(subject.getDepartment()));
        }
        return sameCollege(user, collegeIdOf(subject.getDepartment()));
    }

    public boolean canViewClassroom(Authentication authentication, Long classroomId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication, STAFF_FALLBACK);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (hasRole(user, RoleName.ROLE_FACULTY)) {
            return false;
        }
        if (classroomId == null) {
            return false;
        }
        Classroom classroom = classroomRepository.findById(classroomId).orElse(null);
        if (classroom == null || classroom.getDepartment() == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_HOD)) {
            return sameDepartment(user, classroom.getDepartment().getId())
                && sameCollege(user, collegeIdOf(classroom.getDepartment()));
        }
        return sameCollege(user, collegeIdOf(classroom.getDepartment()));
    }

    public boolean canViewTimetable(Authentication authentication, Long timetableId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication, STAFF_FALLBACK);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (hasRole(user, RoleName.ROLE_FACULTY)) {
            return false;
        }
        if (timetableId == null) {
            return false;
        }
        Timetable timetable = timetableRepository.findById(timetableId).orElse(null);
        if (timetable == null || timetable.getDepartment() == null) {
            return false;
        }
        Department dept = timetable.getDepartment();
        if (hasRole(user, RoleName.ROLE_HOD)) {
            return sameDepartment(user, dept.getId()) && sameCollege(user, collegeIdOf(dept));
        }
        return sameCollege(user, collegeIdOf(dept));
    }

    /** Reads a section's timetable — the section's department must be in-scope. */
    public boolean canViewSectionTimetable(Authentication authentication, Long sectionId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication, STAFF_FALLBACK);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (hasRole(user, RoleName.ROLE_FACULTY)) {
            return false;
        }
        if (sectionId == null) {
            return false;
        }
        Section section = sectionRepository.findById(sectionId).orElse(null);
        if (section == null || section.getAcademicYear() == null
                || section.getAcademicYear().getDepartment() == null) {
            return false;
        }
        Department dept = section.getAcademicYear().getDepartment();
        if (hasRole(user, RoleName.ROLE_HOD)) {
            return sameDepartment(user, dept.getId()) && sameCollege(user, collegeIdOf(dept));
        }
        return sameCollege(user, collegeIdOf(dept));
    }

    /** Reads another faculty member's availability matrix — own college (HOD own dept, FACULTY self). */
    public boolean canReadAvailability(Authentication authentication, Long facultyId) {
        User user = currentUser(authentication);
        if (user == null) {
            return fallbackRoleAllowed(authentication, STAFF_FALLBACK);
        }
        if (hasRole(user, RoleName.ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (facultyId == null) {
            return false;
        }
        Faculty faculty = facultyRepository.findById(facultyId).orElse(null);
        if (faculty == null) {
            return false;
        }
        if (hasRole(user, RoleName.ROLE_HOD)) {
            return faculty.getDepartment() != null
                && sameDepartment(user, faculty.getDepartment().getId())
                && sameCollege(user, collegeIdOf(faculty));
        }
        // Availability is management data — the Faculty role cannot read any
        // availability matrix, even their own (it is set by HOD/admins).
        if (hasRole(user, RoleName.ROLE_FACULTY)) {
            return false;
        }
        return sameCollege(user, collegeIdOf(faculty));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private User currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return userRepository.findById(principal.getId()).orElse(null);
    }

    private Long collegeIdOf(Department dept) {
        return dept != null && dept.getCollege() != null ? dept.getCollege().getId() : null;
    }

    private Long collegeIdOf(Faculty faculty) {
        Long id = faculty != null && faculty.getCollege() != null ? faculty.getCollege().getId() : null;
        if (id == null && faculty != null && faculty.getDepartment() != null) {
            id = collegeIdOf(faculty.getDepartment());
        }
        return id;
    }

    /**
     * Same-college determination. Legacy rows with NO college on either side
     * are treated as the same (default) tenant during the migration window;
     * null on one side only never matches a real college.
     */
    private boolean sameCollege(User user, Long collegeId) {
        if (user == null) {
            return false;
        }
        Long userCollege = user.getCollege() != null ? user.getCollege().getId() : null;
        if (userCollege == null || collegeId == null) {
            return userCollege == null && collegeId == null;
        }
        return userCollege.equals(collegeId);
    }

    /**
     * Coarse role-only fallback used ONLY when the principal is not an app
     * {@link UserPrincipal} — i.e. synthetic principals such as Spring's
     * {@code @WithMockUser} in tests or third-party tokens. No college/department
     * identity is available in those cases, so we revert to the pre-RBAC coarse
     * role rule. Real JWT principals always produce a {@link UserPrincipal} and
     * therefore hit the strict scoped paths above; the production security
     * posture is unchanged.
     */
    private boolean fallbackRoleAllowed(Authentication authentication, RoleName... roles) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        java.util.Set<String> granted = authentication.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .collect(java.util.stream.Collectors.toSet());
        for (RoleName role : roles) {
            if (granted.contains(role.name())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRole(User user, RoleName role) {
        return user != null && user.getRoles().stream().anyMatch(r -> r.getName() == role);
    }

    private boolean sameDepartment(User user, Long departmentId) {
        // A department-restricted HOD with no department is denied with the same
        // actionable message the list endpoints use, instead of a bare 403 that
        // looks like an ordinary ownership mismatch.
        DepartmentScopeResolver.requireHodDepartment(user);
        return user.getDepartment() != null && user.getDepartment().getId().equals(departmentId);
    }
}