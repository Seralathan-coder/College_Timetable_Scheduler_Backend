package com.erp.timetable.module.department.controller;

import com.erp.timetable.common.response.ApiResponse;
import com.erp.timetable.common.response.PageResponse;
import com.erp.timetable.module.department.dto.DepartmentRequest;
import com.erp.timetable.module.department.dto.DepartmentResponse;
import com.erp.timetable.module.department.service.DepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/departments")
@RequiredArgsConstructor
@Tag(name = "Department Management", description = "CRUD endpoints for college departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COLLEGE_ADMIN')")
    @Operation(summary = "Create a new department (SUPER_ADMIN global / COLLEGE_ADMIN own college)")
    public ResponseEntity<ApiResponse<DepartmentResponse>> createDepartment(
            @Valid @RequestBody DepartmentRequest request) {
        DepartmentResponse response = departmentService.createDepartment(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Department created successfully", response));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COLLEGE_ADMIN', 'HOD', 'EXAM_COORDINATOR')")
    @Operation(summary = "Get paginated list of departments (college-scoped)")
    public ResponseEntity<ApiResponse<PageResponse<DepartmentResponse>>> getDepartments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean isArchived,
            @RequestParam(defaultValue = "name,asc") String sort) {
        PageResponse<DepartmentResponse> pageResponse = departmentService.getDepartments(page, size, search, isArchived, sort);
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@rbacGuard.canViewDepartment(authentication, #id)")
    @Operation(summary = "Get department by ID")
    public ResponseEntity<ApiResponse<DepartmentResponse>> getDepartmentById(@PathVariable Long id) {
        DepartmentResponse response = departmentService.getDepartmentById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@rbacGuard.canEditDepartment(authentication, #id)")
    @Operation(summary = "Update department details (HOD has a read-only view of their own department)")
    public ResponseEntity<ApiResponse<DepartmentResponse>> updateDepartment(
            @PathVariable Long id,
            @Valid @RequestBody DepartmentRequest request) {
        DepartmentResponse response = departmentService.updateDepartment(id, request);
        return ResponseEntity.ok(ApiResponse.success("Department updated successfully", response));
    }

    @PatchMapping("/{id}/archive")
    @PreAuthorize("@rbacGuard.canEditDepartment(authentication, #id)")
    @Operation(summary = "Archive department")
    public ResponseEntity<ApiResponse<Void>> archiveDepartment(@PathVariable Long id) {
        departmentService.archiveDepartment(id);
        return ResponseEntity.ok(ApiResponse.successMessage("Department archived successfully"));
    }

    @PatchMapping("/{id}/restore")
    @PreAuthorize("@rbacGuard.canEditDepartment(authentication, #id)")
    @Operation(summary = "Restore archived department")
    public ResponseEntity<ApiResponse<Void>> restoreDepartment(@PathVariable Long id) {
        departmentService.restoreDepartment(id);
        return ResponseEntity.ok(ApiResponse.successMessage("Department restored successfully"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@rbacGuard.canDeleteDepartment(authentication, #id)")
    @Operation(summary = "Delete department permanently (SUPER_ADMIN any / COLLEGE_ADMIN own college only)")
    public ResponseEntity<ApiResponse<Void>> deleteDepartment(@PathVariable Long id) {
        departmentService.deleteDepartment(id);
        return ResponseEntity.ok(ApiResponse.successMessage("Department deleted successfully"));
    }

    @GetMapping("/{id}/dependencies")
    @PreAuthorize("@rbacGuard.canManageDepartment(authentication, #id)")
    @Operation(summary = "Get dependency counts for a department before deletion")
    public ResponseEntity<ApiResponse<java.util.Map<String, Integer>>> getDependencies(@PathVariable Long id) {
        java.util.Map<String, Integer> counts = departmentService.getDependencyCounts(id);
        return ResponseEntity.ok(ApiResponse.success(counts));
    }
}
