package com.erp.timetable.module.faculty.dto;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FacultyResponse {
    private Long id;
    private String employeeId;
    private String firstName;
    private String lastName;
    private String fullName;
    private String email;
    private String phone;
    private Long departmentId;
    private String departmentName;
    private String designation;
    private String qualification;
    private String specialization;
    private Integer maxDailyHours;
    private Integer maxWeeklyHours;
    private String status;
    private Instant createdAt;
}
