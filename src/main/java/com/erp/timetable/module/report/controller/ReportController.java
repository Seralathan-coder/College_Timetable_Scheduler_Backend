package com.erp.timetable.module.report.controller;

import com.erp.timetable.common.response.ApiResponse;
import com.erp.timetable.module.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
@Tag(name = "Report Centre", description = "Endpoints for timetable & room utilization reporting")
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/faculty/{id}")
    @PreAuthorize("@rbacGuard.canViewFacultyReport(authentication, #id)")
    @Operation(summary = "Get schedule report for a faculty member")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFacultyReport(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getFacultyReport(id)));
    }

    @GetMapping("/rooms/utilization")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COLLEGE_ADMIN', 'HOD', 'EXAM_COORDINATOR')")
    @Operation(summary = "Get classroom utilization summary report (college-scoped for College Admins)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRoomUtilization() {
        return ResponseEntity.ok(ApiResponse.success(reportService.getRoomUtilizationReport()));
    }
}
