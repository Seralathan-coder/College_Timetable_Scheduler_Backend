package com.erp.timetable.config.security;

import com.erp.timetable.common.exception.BusinessException;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the department an authenticated HOD is allowed to work with, and
 * enforces that department on list filters and on the parent references of
 * create/update bodies.
 *
 * <p>The scope is derived exclusively from the DB-loaded
 * {@link User#department} column (the relationship written by
 * {@code DepartmentService.createHodLogin}), never from client-supplied
 * ids. Nothing here is hardcoded to a specific department, college or user.
 *
 * <p>Accounts that also hold a college-administration role
 * ({@code ROLE_COLLEGE_ADMIN} / {@code ROLE_SUPER_ADMIN}) are NOT restricted:
 * the broader management grant wins, which keeps this policy consistent with
 * the frontend {@code useHodScope} hook.
 */
@Component
@RequiredArgsConstructor
public class DepartmentScopeResolver {

    /**
     * The single message used for every "HOD without a department" denial, so
     * the list endpoints, the per-id guards and {@code /timetable/my} all
     * report the same actionable cause.
     */
    public static final String HOD_UNASSIGNED_MESSAGE =
            "Your HOD account is not linked to a department. Please contact your college administrator.";

    private final TenantContext tenantContext;

    /**
     * True when {@code user} is an HOD that must be confined to a single
     * department. An account that also holds a college/platform administration
     * role keeps the broader grant, matching {@code useHodScope.isHodReadOnly}.
     */
    public static boolean isDepartmentRestrictedHod(User user) {
        return user != null
                && hasRole(user, RoleName.ROLE_HOD)
                && !hasRole(user, RoleName.ROLE_COLLEGE_ADMIN)
                && !hasRole(user, RoleName.ROLE_SUPER_ADMIN);
    }

    /**
     * Denies a department-restricted HOD that has no department, with the
     * shared message. No-op for every other account, so administration roles
     * without a department keep working exactly as before.
     *
     * @throws BusinessException when the caller is a restricted HOD whose
     *         {@code users.department_id} is empty
     */
    public static void requireHodDepartment(User user) {
        if (isDepartmentRestrictedHod(user)
                && (user.getDepartment() == null || user.getDepartment().getId() == null)) {
            throw new BusinessException(HOD_UNASSIGNED_MESSAGE);
        }
    }

    private static boolean hasRole(User user, RoleName role) {
        return user != null && user.getRoles().stream().anyMatch(r -> r.getName() == role);
    }

    /**
     * The single department the caller is confined to, or {@code null} when
     * the caller is not an HOD (or holds a broader management role) and
     * therefore must not be restricted.
     *
     * @throws BusinessException when an HOD account is not linked to any
     *         department. Denying is deliberate: silently falling back to a
     *         college-wide or guessed department would leak another
     *         department's data.
     */
    @Transactional(readOnly = true)
    public Long restrictedDepartmentId() {
        User caller = tenantContext.currentUser();
        if (!isDepartmentRestrictedHod(caller)) {
            return null;
        }
        requireHodDepartment(caller);
        return caller.getDepartment().getId();
    }

    /**
     * Resolves the department filter a list query must apply.
     *
     * <p>For an HOD the answer is always their own department. An explicit
     * filter for a different department is rejected rather than silently
     * ignored, so a caller never believes it is looking at another
     * department's rows.
     *
     * @return the requested id for unrestricted callers, otherwise the HOD's
     *         own department id
     */
    @Transactional(readOnly = true)
    public Long effectiveFilterDepartmentId(Long requestedDepartmentId) {
        Long restricted = restrictedDepartmentId();
        if (restricted == null) {
            return requestedDepartmentId;
        }
        if (requestedDepartmentId != null && !requestedDepartmentId.equals(restricted)) {
            throw new BusinessException("You may only view records belonging to your own department.");
        }
        return restricted;
    }

    /** A broader college-administration grant overrides HOD read-only scope. */
    private boolean isDepartmentAdministrator(User user) {
        return user.hasRole(RoleName.ROLE_COLLEGE_ADMIN) || user.hasRole(RoleName.ROLE_SUPER_ADMIN);
    }

    /**
     * The target department of a create/update body must be in scope.
     * A {@code null} department means "not supplied", which is not a
     * cross-department reference and is left to each service's own
     * required-field validation.
     */
    public void assertDepartmentInScope(Department department) {
        Long restricted = restrictedDepartmentId();
        if (restricted == null || department == null) {
            return;
        }
        if (!restricted.equals(department.getId())) {
            throw new BusinessException("You may only manage records belonging to your own department.");
        }
    }

    /**
     * Update-path guard: a department-restricted HOD may neither move a record
     * into another department nor <em>detach</em> it by submitting a
     * {@code null} department, which would silently take the record out of the
     * only scope they may manage.
     *
     * <p>Callers that are not department-restricted (SUPER_ADMIN,
     * COLLEGE_ADMIN, EXAM_COORDINATOR, FACULTY) are unaffected, so legitimate
     * administrative re-parenting — including clearing a department — keeps
     * working. On create the controller guard already rejects a null
     * department for an HOD, so this is applied to the update paths.
     */
    public void assertNotDetached(Department department) {
        Long restricted = restrictedDepartmentId();
        if (restricted == null) {
            return;
        }
        if (department == null) {
            throw new BusinessException(
                    "You cannot remove a record from your department. Reassign it to a department you manage.");
        }
        assertDepartmentInScope(department);
    }

    /**
     * The academic year of a create/update body must belong to the HOD's
     * department. Optional on entities that allow a year-less record, so a
     * {@code null} is not treated as a violation.
     */
    public void assertAcademicYearInScope(AcademicYear academicYear) {
        Long restricted = restrictedDepartmentId();
        if (restricted == null || academicYear == null) {
            return;
        }
        if (academicYear.getDepartment() == null
                || !restricted.equals(academicYear.getDepartment().getId())) {
            throw new BusinessException("The selected academic year does not belong to your department.");
        }
    }

    /**
     * The section of a create/update body must belong to the HOD's department.
     * A {@code null} section (a record that spans all sections) is allowed.
     */
    public void assertSectionInScope(Section section) {
        Long restricted = restrictedDepartmentId();
        if (restricted == null || section == null) {
            return;
        }
        if (section.getAcademicYear() == null
                || section.getAcademicYear().getDepartment() == null
                || !restricted.equals(section.getAcademicYear().getDepartment().getId())) {
            throw new BusinessException("The selected section does not belong to your department.");
        }
    }

    /**
     * The faculty member assigned to a subject must belong to the SAME COLLEGE as
     * the subject's department. Cross-department assignment inside one college is
     * allowed (an HOD may draw on any department of their own college); assigning
     * faculty from ANOTHER college is never allowed, for any caller. A {@code
     * null} faculty (an unassigned subject) or an unresolvable college is a no-op.
     */
    public void assertFacultyAssignable(Department department, Faculty faculty) {
        if (faculty == null) {
            return;
        }
        Long departmentCollegeId = department != null && department.getCollege() != null
                ? department.getCollege().getId() : null;
        Long facultyCollegeId = faculty.getCollege() != null
                ? faculty.getCollege().getId()
                : (faculty.getDepartment() != null && faculty.getDepartment().getCollege() != null
                    ? faculty.getDepartment().getCollege().getId() : null);
        if (departmentCollegeId != null && facultyCollegeId != null
                && !departmentCollegeId.equals(facultyCollegeId)) {
            throw new BusinessException(
                    "The selected faculty member belongs to a different college and cannot be assigned to this subject.");
        }
    }
}
