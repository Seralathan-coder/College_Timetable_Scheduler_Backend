package com.erp.timetable.module.department.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepartmentRequest {

    @NotBlank(message = "Department name is required")
    @Size(max = 150, message = "Department name cannot exceed 150 characters")
    private String name;

    @Size(max = 150, message = "HOD name cannot exceed 150 characters")
    private String hodName;

    @Size(max = 255, message = "Contact email cannot exceed 255 characters")
    private String contactEmail;

    @Size(max = 20, message = "Contact phone cannot exceed 20 characters")
    private String contactPhone;

    @Size(max = 100, message = "Building cannot exceed 100 characters")
    private String building;

    @Size(max = 5000, message = "Description cannot exceed 5000 characters")
    private String description;

    /**
     * Optional HOD login credentials created together with the department.
     * When provided on create, the system provisions a ROLE_HOD user bound to
     * this department with these credentials. Ignored on update and when blank.
     */
    @Size(max = 100, message = "HOD Login ID cannot exceed 100 characters")
    private String hodUsername;

    @Size(min = 6, max = 72, message = "HOD Password must be between 6 and 72 characters")
    private String hodPassword;

    /**
     * Per-year section selection. Every department always contains four years
     * (1st, 2nd, 3rd, 4th); each year lists only the sections (A–E) that exist.
     * Example: years = [
     *   { yearLabel: "1st Year", sections: ["A", "B"] },
     *   { yearLabel: "2nd Year", sections: ["A", "B", "C"] },
     *   { yearLabel: "3rd Year", sections: ["A", "B", "C", "D"] },
     *   { yearLabel: "4th Year", sections: ["A", "B", "C", "D", "E"] }
     * ]
     */
    @Builder.Default
    private List<YearSectionsRequest> years = List.of();
}
