package com.erp.timetable.module.timetable.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimetableResponse {

    private Long id;
    private String academicSession;
    private Long departmentId;
    private String departmentName;
    private Long academicYearId;
    private String yearLabel;
    private Long sectionId;
    private String sectionName;
    private Integer semester;
    private String status;
    private Integer conflictCount;
    private Integer optimizationScore;
    private String createdAt;
    private List<TimetableEntryDto> entries;
    private List<TimetableConflictDto> conflicts;
}
