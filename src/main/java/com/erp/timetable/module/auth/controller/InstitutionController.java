package com.erp.timetable.module.auth.controller;

import com.erp.timetable.common.response.ApiResponse;
import com.erp.timetable.module.auth.dto.InstitutionRequest;
import com.erp.timetable.module.auth.dto.InstitutionResponse;
import com.erp.timetable.module.auth.service.InstitutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * College/institution information endpoints.
 *
 * SECURITY: the endpoints operate on the CALLER'S OWN college — resolved from
 * the authenticated user, never from the request. A client cannot point the
 * endpoint at another college. Updates require a College Admin (own college)
 * or SUPER_ADMIN, enforced server-side via {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/institution")
@RequiredArgsConstructor
@Tag(name = "Institution", description = "Current user's college information (own-college, College Admin / SUPER_ADMIN-controlled)")
public class InstitutionController {

    private final InstitutionService institutionService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the current user's college information (any authenticated user)")
    public ResponseEntity<ApiResponse<InstitutionResponse>> getInstitution() {
        return ResponseEntity.ok(ApiResponse.success(institutionService.getInstitution()));
    }

    @PutMapping
    @PreAuthorize("@rbacGuard.canManageInstitution(authentication)")
    @Operation(summary = "Update the current user's college information (College Admin / SUPER_ADMIN)")
    public ResponseEntity<ApiResponse<InstitutionResponse>> updateInstitution(
            @Valid @RequestBody InstitutionRequest request) {
        InstitutionResponse response = institutionService.updateInstitution(request);
        return ResponseEntity.ok(ApiResponse.success("Institution updated", response));
    }
}