package com.erp.timetable.module.department.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcademicYearDto {
    private Long id;
    private Long departmentId;
    private String yearLabel;
    private Boolean isEnabled;
    private List<SectionDto> sections;
}
