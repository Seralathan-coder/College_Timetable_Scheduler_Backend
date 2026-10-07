package com.erp.timetable.module.auth.controller;

import com.erp.timetable.common.response.ApiResponse;
import com.erp.timetable.module.auth.dto.CollegeAdminAccountRequest;
import com.erp.timetable.module.auth.dto.CollegeRequest;
import com.erp.timetable.module.auth.dto.CollegeResponse;
import com.erp.timetable.module.auth.service.CollegeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Tenant college endpoints — SUPER_ADMIN platform operations only. College
 * Admins manage their own college through the existing management endpoints,
 * scoped server-side by {@code college_id}.
 */
@RestController
@RequestMapping("/colleges")
@RequiredArgsConstructor
@Tag(name = "Colleges", description = "Platform-level tenant college management (SUPER_ADMIN only)")
public class CollegeController {

    private final CollegeService collegeService;

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Create a tenant college together with its College Admin account (SUPER_ADMIN only)")
    public ResponseEntity<ApiResponse<CollegeResponse>> createCollege(
            @Valid @RequestBody CollegeRequest request) {
        CollegeResponse response = collegeService.createCollege(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("College created successfully", response));
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "List all tenant colleges (SUPER_ADMIN only)")
    public ResponseEntity<ApiResponse<List<CollegeResponse>>> listColleges() {
        return ResponseEntity.ok(ApiResponse.success(collegeService.listColleges()));
    }

    @PostMapping("/{id}/admin-account")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Create a College Admin login ID + password for a specific college (SUPER_ADMIN only)")
    public ResponseEntity<ApiResponse<CollegeResponse>> createCollegeAdminAccount(
            @PathVariable Long id,
            @Valid @RequestBody CollegeAdminAccountRequest request) {
        CollegeResponse response = collegeService.createCollegeAdminAccount(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("College Admin account created", response));
    }
}