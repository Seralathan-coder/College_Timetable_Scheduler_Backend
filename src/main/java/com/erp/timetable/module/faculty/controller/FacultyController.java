package com.erp.timetable.module.faculty.controller;

import com.erp.timetable.common.response.ApiResponse;
import com.erp.timetable.common.response.PageResponse;
import com.erp.timetable.module.faculty.dto.FacultyRequest;
import com.erp.timetable.module.faculty.dto.FacultyResponse;
import com.erp.timetable.module.faculty.service.FacultyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/faculty")
@RequiredArgsConstructor
@Tag(name = "Faculty Management", description = "CRUD endpoints for faculty profiles")
public class FacultyController {

    private final FacultyService facultyService;

    @PostMapping
    @PreAuthorize("@rbacGuard.canCreateFaculty(authentication, #request.departmentId)")
    @Operation(summary = "Create a new faculty profile")
    public ResponseEntity<ApiResponse<FacultyResponse>> createFaculty(
            @Valid @RequestBody FacultyRequest request) {
        FacultyResponse response = facultyService.createFaculty(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Faculty created successfully", response));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COLLEGE_ADMIN', 'HOD', 'EXAM_COORDINATOR')")
    @Operation(summary = "Get paginated list of faculty (college-scoped)")
    public ResponseEntity<ApiResponse<PageResponse<FacultyResponse>>> getFaculty(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "firstName,asc") String sort) {
        PageResponse<FacultyResponse> pageResponse = facultyService.getFaculty(page, size, search, departmentId, status, sort);
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    @GetMapping("/assignable")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COLLEGE_ADMIN', 'HOD')")
    @Operation(summary = "List faculty selectable for subject assignment (own college, any department)")
    public ResponseEntity<ApiResponse<List<FacultyResponse>>> getAssignableFaculty(
            @RequestParam(required = false) Long departmentId) {
        List<FacultyResponse> response = facultyService.getAssignableFaculty(departmentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@rbacGuard.canViewFaculty(authentication, #id)")
    @Operation(summary = "Get faculty by ID")
    public ResponseEntity<ApiResponse<FacultyResponse>> getFacultyById(@PathVariable Long id) {
        FacultyResponse response = facultyService.getFacultyById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@rbacGuard.canManageFaculty(authentication, #id)")
    @Operation(summary = "Update faculty profile")
    public ResponseEntity<ApiResponse<FacultyResponse>> updateFaculty(
            @PathVariable Long id,
            @Valid @RequestBody FacultyRequest request) {
        FacultyResponse response = facultyService.updateFaculty(id, request);
        return ResponseEntity.ok(ApiResponse.success("Faculty updated successfully", response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@rbacGuard.canDeleteFaculty(authentication, #id)")
    @Operation(summary = "Delete faculty profile (SUPER_ADMIN or own-college COLLEGE_ADMIN)")
    public ResponseEntity<ApiResponse<Void>> deleteFaculty(@PathVariable Long id) {
        facultyService.deleteFaculty(id);
        return ResponseEntity.ok(ApiResponse.successMessage("Faculty deleted successfully"));
    }
}
