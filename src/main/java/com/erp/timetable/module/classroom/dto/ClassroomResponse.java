package com.erp.timetable.module.classroom.dto;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClassroomResponse {
    private Long id;
    private String roomNumber;
    private String roomName;
    private String building;
    private Long departmentId;
    private String departmentName;
    private Long academicYearId;
    private String academicYearLabel;
    private Long sectionId;
    private String sectionName;
    private String roomType;
    private Integer capacity;
    private Integer floor;
    private String status;
    private Instant createdAt;
}
