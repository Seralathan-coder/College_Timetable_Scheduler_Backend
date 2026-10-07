package com.erp.timetable.common.exception;

import com.erp.timetable.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralised exception handling for all REST controllers.
 * Maps exceptions to HTTP status codes and a consistent ApiResponse envelope.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── 404 Not Found ──────────────────────────────────────────────────
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(
            ResourceNotFoundException ex, WebRequest request) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error(ex.getMessage()));
    }

    // ── 404 Unmapped Path ──────────────────────────────────────────────
    // Reaches an endpoint that does not exist (falls through to the static
    // resource handler). Return 404 instead of the generic 500 catch-all.
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        log.warn("No handler mapped for path: {}", ex.getResourcePath());
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error("Endpoint not found: " + ex.getResourcePath()));
    }

    // ── 409 Conflict ───────────────────────────────────────────────────
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(
            ConflictException ex, WebRequest request) {
        log.warn("Conflict: {}", ex.getMessage());
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ApiResponse.error(ex.getMessage()));
    }

    // ── 400 Validation (Bean Validation) ──────────────────────────────
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
            fieldErrors.put(field, error.getDefaultMessage());
        });
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error("Validation failed", fieldErrors));
    }

    // ── 400 Constraint Violation ───────────────────────────────────────
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getConstraintViolations().forEach(cv ->
            errors.put(cv.getPropertyPath().toString(), cv.getMessage()));
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error("Constraint violation", errors));
    }

    // ── 409/400 Database Integrity ──────────────────────────────────────
    // Distinguishes foreign-key, unique-constraint, length and NOT-NULL
    // violations so the UI never sees a misleading "duplicate value" error
    // for an unrelated database failure.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(
            DataIntegrityViolationException ex) {
        String root = resolveRootMessage(ex);
        String lower = root.toLowerCase();
        log.error("Data integrity violation: {}", root);

        HttpStatus status = HttpStatus.CONFLICT;
        String message;
        if (lower.contains("referential integrity") || lower.contains("foreign key")) {
            message = "Cannot delete or modify: this record is still referenced by other records. "
                + "Remove or reassign the related records (subjects, faculty, timetables) first.";
        } else if (lower.contains("unique") || lower.contains("duplicate")) {
            message = "A record with the same unique value already exists.";
        } else if (lower.contains("too long")) {
            status = HttpStatus.BAD_REQUEST;
            message = "One of the values is too long for its field. Please shorten it and try again.";
        } else if (lower.contains("not null")) {
            status = HttpStatus.BAD_REQUEST;
            message = "A required value is missing. Please fill in all required fields.";
        } else {
            message = "A data integrity error occurred: " + root;
        }
        return ResponseEntity
            .status(status)
            .body(ApiResponse.error(message));
    }

    // ── 401 Bad Credentials ────────────────────────────────────────────
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.error("Invalid username or password."));
    }

    // ── 403 Access Denied ──────────────────────────────────────────────
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.error("You do not have permission to perform this action."));
    }

    // ── 422 Business Rule Violation ────────────────────────────────────
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("Business exception: {}", ex.getMessage());
        return ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ApiResponse.error(ex.getMessage()));
    }

    // ── 413 Upload Too Large ────────────────────────────────────────────
    // Multipart request exceeds spring.servlet.multipart limits (5 MB).
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected: file too large");
        return ResponseEntity
            .status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ApiResponse.error("Uploaded file exceeds the maximum allowed size (5 MB)."));
    }

    // ── 500 Timetable Generation Error ─────────────────────────────────
    @ExceptionHandler(TimetableGenerationException.class)
    public ResponseEntity<ApiResponse<Void>> handleTimetableGeneration(
            TimetableGenerationException ex) {
        log.error("Timetable generation failed: {}", ex.getMessage());
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("Timetable generation failed: " + ex.getMessage()));
    }

    // ── 500 Catch-all ──────────────────────────────────────────────────
    // Surfaces the actual root-cause message (sanitized/truncated) instead of
    // a generic "contact support" line that hides the real failure.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAll(Exception ex, WebRequest request) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        String root = resolveRootMessage(ex);
        if (root == null || root.isBlank()) {
            root = ex.getClass().getSimpleName();
        }
        if (root.length() > 500) {
            root = root.substring(0, 500) + "…";
        }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("An unexpected error occurred: " + root));
    }

    /** Returns the deepest root-cause message for the given exception. */
    private String resolveRootMessage(Exception ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return (message == null || message.isBlank()) ? ex.getMessage() : message;
    }
}
