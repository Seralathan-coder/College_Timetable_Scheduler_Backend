package com.erp.timetable.module.department.dto;

import lombok.*;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepartmentResponse {
    private Long id;
    private String name;
    private String hodName;
    private String contactEmail;
    private String contactPhone;
    private String building;
    private String description;
    private Boolean isArchived;
    private Long collegeId;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private List<AcademicYearDto> academicYears;
}
