package com.erp.timetable.module.timetable.planning.model;

import lombok.*;

/**
 * A subject as a Timefold problem fact. Carries the curriculum attributes the
 * solver needs (weekly demand, block size, faculty binding) without any JPA
 * coupling, so the planning model stays detached from the persistence layer.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlannableSubject {

    private Long subjectId;
    private String subjectCode;
    private String subjectName;
    private String subjectType; // THEORY, LAB
    private Integer semester;
    private Long departmentId;
    private Long sectionId;
    private Long assignedFacultyId;
    private Integer theoryHours;
    private Integer practicalHours;
    private Integer sessionBlockSize;
    private Integer weeklyHours;
    private boolean active;
}
