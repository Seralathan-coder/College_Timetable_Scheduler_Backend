package com.erp.timetable.module.timetable.engine.constraint;

import com.erp.timetable.module.faculty.entity.Faculty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;

@Component
public class DepartmentPermissionConstraint implements SchedulingConstraint {

    @Override
    public String getConstraintName() {
        return "DEPARTMENT_PERMISSION";
    }

    @Override
    public boolean isSatisfied(CandidatePlacement placement, ConstraintContext context) {
        Faculty faculty = placement.getFaculty();
        Long targetDepartmentId = placement.getDepartmentId();

        if (faculty == null || targetDepartmentId == null) return false;

        // 0. Explicitly assigned faculty is always eligible for the subject,
        //    even when the primary department differs from the target department.
        if (placement.getSubject() != null && placement.getSubject().getAssignedFaculty() != null
                && Objects.equals(placement.getSubject().getAssignedFaculty().getId(), faculty.getId())) {
            return true;
        }

        // 1. Primary Department Match
        if (faculty.getDepartment() != null && Objects.equals(faculty.getDepartment().getId(), targetDepartmentId)) {
            return true;
        }

        // 2. Shared Teaching Department Match
        if (faculty.getTeachingDepartments() != null && !faculty.getTeachingDepartments().isBlank()) {
            return Arrays.stream(faculty.getTeachingDepartments().split(","))
                .map(String::trim)
                .anyMatch(deptIdOrName -> deptIdOrName.equals(String.valueOf(targetDepartmentId)) ||
                    (faculty.getDepartment() != null && faculty.getDepartment().getName().equalsIgnoreCase(deptIdOrName)));
        }

        return false;
    }

    @Override
    public String getViolationMessage(CandidatePlacement placement) {
        return "Faculty " + (placement.getFaculty() != null ? placement.getFaculty().getFullName() : "N/A") +
            " does not have permission to teach in target department ID " + placement.getDepartmentId();
    }
}
