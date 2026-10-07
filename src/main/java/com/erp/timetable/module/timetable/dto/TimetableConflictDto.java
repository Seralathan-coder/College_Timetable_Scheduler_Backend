package com.erp.timetable.module.timetable.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimetableConflictDto {
    private Long id;
    private String conflictType;
    private String description;
    private String severity;
}
