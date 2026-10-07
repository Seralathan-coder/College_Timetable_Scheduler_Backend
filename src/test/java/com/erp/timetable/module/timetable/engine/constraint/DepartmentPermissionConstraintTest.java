package com.erp.timetable.module.timetable.engine.constraint;

import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.subject.entity.Subject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the cross-department rule in the Greedy
 * {@link DepartmentPermissionConstraint}: the subject's explicitly assigned
 * faculty is always eligible, while every other faculty must still pass the
 * existing department-permission rules.
 */
class DepartmentPermissionConstraintTest {

    private final DepartmentPermissionConstraint constraint = new DepartmentPermissionConstraint();

    private static Department dept(long id, String name) {
        return Department.builder().id(id).name(name).build();
    }

    private static Faculty faculty(long id, Department department) {
        return Faculty.builder()
            .id(id)
            .firstName("Faculty")
            .lastName(String.valueOf(id))
            .department(department)
            .status("AVAILABLE")
            .build();
    }

    private static CandidatePlacement placement(Subject subject, Faculty faculty, Long departmentId) {
        return CandidatePlacement.builder()
            .subject(subject)
            .faculty(faculty)
            .departmentId(departmentId)
            .slots(List.of())
            .build();
    }

    @Test
    void explicitlyAssignedFacultyFromDifferentDepartment_isAllowed() {
        Department target = dept(1L, "CSE");
        Department assignedDept = dept(2L, "CSD");
        Faculty assigned = faculty(10L, assignedDept);
        Subject subject = Subject.builder().id(100L).assignedFaculty(assigned).build();

        assertTrue(constraint.isSatisfied(placement(subject, assigned, target.getId()), null),
            "the explicitly assigned faculty must teach the subject even when its primary department differs");
    }

    @Test
    void nonAssignedFacultyFromDifferentDepartment_isStillRejected() {
        Department target = dept(1L, "CSE");
        Department assignedDept = dept(2L, "CSD");
        Faculty assigned = faculty(10L, assignedDept);
        Faculty other = faculty(11L, assignedDept);
        Subject subject = Subject.builder().id(100L).assignedFaculty(assigned).build();

        assertFalse(constraint.isSatisfied(placement(subject, other, target.getId()), null),
            "a faculty that is NOT the subject's assigned faculty must still pass the department rules");
    }

    @Test
    void facultyFromPrimaryDepartment_isStillAllowed() {
        Department target = dept(1L, "CSE");
        Department assignedDept = dept(2L, "CSD");
        Faculty assigned = faculty(10L, assignedDept);
        Faculty inTarget = faculty(12L, target);
        Subject subject = Subject.builder().id(100L).assignedFaculty(assigned).build();

        assertTrue(constraint.isSatisfied(placement(subject, inTarget, target.getId()), null),
            "the primary-department match must keep working");
    }

    @Test
    void assignedFacultyFromSameDepartment_isStillAllowed() {
        Department target = dept(1L, "CSE");
        Faculty assigned = faculty(10L, target);
        Subject subject = Subject.builder().id(100L).assignedFaculty(assigned).build();

        assertTrue(constraint.isSatisfied(placement(subject, assigned, target.getId()), null));
    }

    @Test
    void subjectWithoutAssignedFacultyFromDifferentDepartment_isRejected() {
        Department target = dept(1L, "CSE");
        Faculty other = faculty(11L, dept(2L, "CSD"));
        Subject subject = Subject.builder().id(100L).build();

        assertFalse(constraint.isSatisfied(placement(subject, other, target.getId()), null),
            "without an explicit assignment the old department rule must apply unchanged");
    }
}
