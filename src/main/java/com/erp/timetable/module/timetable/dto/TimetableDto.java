package com.erp.timetable.module.timetable.dto;

import lombok.*;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimetableDto {
    private Long id;
    private Long departmentId;
    private String departmentName;
    private Long academicYearId;
    private String yearLabel;
    private Long sectionId;
    private String sectionName;
    private Integer semester;
    private String academicSession;
    private String status;
    private Integer conflictCount;
    private Instant createdAt;
    private List<TimetableEntryDto> entries;
    private List<TimetableConflictDto> conflicts;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimetableEntryDto {
        private Long id;
        private String dayOfWeek;
        private Long timeSlotId;
        private Integer slotOrder;
        private String startTime;
        private String endTime;
        private Long subjectId;
        private String subjectCode;
        private String subjectName;
        private String subjectType;
        private Long facultyId;
        private String facultyName;
        private Long classroomId;
        private String roomNumber;
        private String building;
        private Boolean isLocked;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimetableConflictDto {
        private Long id;
        private String conflictType;
        private String description;
        private String severity;
    }
}
