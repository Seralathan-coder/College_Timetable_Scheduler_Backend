package com.erp.timetable.module.timetable.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimetableEntryDto {
    private Long id;
    private String dayOfWeek;
    private Long timeSlotId;
    private String timeSlotLabel;
    private String timeSlotTime;
    private Long subjectId;
    private String subjectCode;
    private String subjectName;
    private String subjectType;
    private Long facultyId;
    private String facultyName;
    private Long classroomId;
    private String roomNumber;
    private String roomName;
    private Boolean isLocked;
    private Boolean isLab;
}
