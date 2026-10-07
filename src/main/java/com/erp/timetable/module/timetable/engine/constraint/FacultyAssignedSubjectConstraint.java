package com.erp.timetable.module.timetable.engine.constraint;

import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.subject.entity.Subject;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class FacultyAssignedSubjectConstraint implements SchedulingConstraint {

    @Override
    public String getConstraintName() {
        return "FACULTY_ASSIGNED_SUBJECT";
    }

    @Override
    public boolean isSatisfied(CandidatePlacement placement, ConstraintContext context) {
        Faculty faculty = placement.getFaculty();
        Subject subject = placement.getSubject();

        if (faculty == null || subject == null) return false;

        // 1. Direct subject assignment
        if (subject.getAssignedFaculty() != null && subject.getAssignedFaculty().getId().equals(faculty.getId())) {
            return true;
        }

        // 2. Assigned subject codes list on faculty (between 2 and 4 subjects)
        if (faculty.getAssignedSubjectCodes() != null && !faculty.getAssignedSubjectCodes().isBlank()) {
            boolean codeMatched = Arrays.stream(faculty.getAssignedSubjectCodes().split(","))
                .map(String::trim)
                .anyMatch(code -> code.equalsIgnoreCase(subject.getSubjectCode()));
            if (codeMatched) return true;
        }

        // 3. Specialization keyword match
        if (faculty.getSpecialization() != null && !faculty.getSpecialization().isBlank()) {
            String spec = faculty.getSpecialization().toLowerCase();
            String name = subject.getSubjectName().toLowerCase();
            if (name.contains(spec) || spec.contains(name.substring(0, Math.min(4, name.length())))) {
                return true;
            }
        }

        // If direct assigned faculty is unset, allow faculty in department
        return subject.getAssignedFaculty() == null;
    }

    @Override
    public String getViolationMessage(CandidatePlacement placement) {
        return "Faculty " + (placement.getFaculty() != null ? placement.getFaculty().getFullName() : "N/A") +
            " is not assigned to teach subject " + (placement.getSubject() != null ? placement.getSubject().getSubjectCode() : "N/A");
    }
}
