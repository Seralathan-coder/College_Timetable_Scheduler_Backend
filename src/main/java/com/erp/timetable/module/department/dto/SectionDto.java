package com.erp.timetable.module.department.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SectionDto {
    private Long id;
    private Long academicYearId;
    private String name;
    private Integer studentStrength;
    private Long facultyAdvisorId;
    private String status;
}
