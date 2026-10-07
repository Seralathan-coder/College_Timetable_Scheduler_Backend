package com.erp.timetable.module.timetable.planning.model;

import lombok.*;

/**
 * A faculty member as a Timefold problem fact. Faculty is a hard-binding of a
 * lesson (each subject has an assigned faculty), so it is not a planning
 * variable — it constrains which lessons a person may be assigned to.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlannableFaculty {

    private Long facultyId;
    private String employeeId;
    private String fullName;
    private Long departmentId;
    private String departmentName;
    private String teachingDepartments; // Comma separated IDs/Names of shared departments
    private String specialization;
    private String assignedSubjectCodes; // Comma separated subject codes
    private Integer maxDailyHours;
    private Integer maxWeeklyHours;
    private String status; // AVAILABLE, BUSY, LEAVE
}
