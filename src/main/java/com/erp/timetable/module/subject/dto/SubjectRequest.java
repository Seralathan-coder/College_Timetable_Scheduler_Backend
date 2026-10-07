package com.erp.timetable.module.subject.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubjectRequest {

    @NotBlank(message = "Subject code is required")
    @Size(max = 30, message = "Subject code cannot exceed 30 characters")
    private String subjectCode;

    @NotBlank(message = "Subject name is required")
    @Size(max = 200, message = "Subject name cannot exceed 200 characters")
    private String subjectName;

    private Long departmentId;
    private Long academicYearId;
    private Long sectionId;
    private Long facultyId;

    @NotNull(message = "Semester is required")
    @Min(value = 1, message = "Semester must be at least 1")
    private Integer semester;

    @NotNull(message = "Credits are required")
    @Min(value = 1, message = "Credits must be at least 1")
    @Builder.Default
    private Integer credits = 3;

    @NotNull(message = "Theory hours are required")
    @Min(value = 0, message = "Theory hours cannot be negative")
    @Builder.Default
    private Integer theoryHours = 3;

    @NotNull(message = "Practical hours are required")
    @Min(value = 0, message = "Practical hours cannot be negative")
    @Builder.Default
    private Integer practicalHours = 0;

    @Pattern(regexp = "THEORY|LAB|GAME|OTHER",
             message = "Subject type must be one of THEORY, LAB, GAME, OTHER")
    @Builder.Default
    private String subjectType = "THEORY";

    /**
     * Consecutive periods per session (block size). 1 = single periods spread
     * across days (default). 2 = two back-to-back periods per session.
     */
    @NotNull(message = "Consecutive periods per session is required")
    @Min(value = 1, message = "Consecutive periods per session must be at least 1")
    @jakarta.validation.constraints.Max(value = 2, message = "Consecutive periods per session cannot exceed 2")
    @Builder.Default
    private Integer sessionBlockSize = 1;

    @Builder.Default
    private Boolean isActive = true;

    private Integer totalSemesterHours;

    private Integer teachingWeeks;
}
