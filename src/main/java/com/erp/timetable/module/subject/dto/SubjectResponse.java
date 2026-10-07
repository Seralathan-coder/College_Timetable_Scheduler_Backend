package com.erp.timetable.module.subject.dto;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubjectResponse {
    private Long id;
    private String subjectCode;
    private String subjectName;
    private Long departmentId;
    private String departmentName;
    private Long academicYearId;
    private String yearLabel;
    private Long sectionId;
    private String sectionName;
    private Long facultyId;
    private String facultyName;
    private Integer semester;
    private Integer credits;
    private Integer theoryHours;
    private Integer practicalHours;
    private String subjectType;
    private Integer sessionBlockSize;
    private Boolean isActive;
    private Integer totalSemesterHours;
    private Integer teachingWeeks;
    private Instant createdAt;
}
