package com.erp.timetable.module.dashboard.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardStatsResponse {

    private long totalDepartments;
    private long totalFaculty;
    private long totalSubjects;
    private long totalClassrooms;
    private long totalTimetables;

    private List<FacultyWorkloadDto> facultyWorkload;
    private List<RoomUtilizationDto> roomUtilization;
    private List<TodayClassDto> todayClasses;
    private List<RecentActivityDto> recentActivities;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FacultyWorkloadDto {
        private String name;
        private int hours;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RoomUtilizationDto {
        private String name;
        private long value;
        private String color;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TodayClassDto {
        private String time;
        private String subject;
        private String faculty;
        private String room;
        private String dept;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RecentActivityDto {
        private String type;
        private String msg;
        private String time;
    }
}
