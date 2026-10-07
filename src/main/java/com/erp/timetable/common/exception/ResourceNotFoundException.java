package com.erp.timetable.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
    public ResourceNotFoundException(String entity, Long id) {
        super(entity + " not found with id: " + id);
    }
    public ResourceNotFoundException(String entity, String field, Object value) {
        super(entity + " not found with " + field + ": " + value);
    }
}
