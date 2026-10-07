package com.erp.timetable.common.exception;

public class TimetableGenerationException extends RuntimeException {
    public TimetableGenerationException(String message) {
        super(message);
    }
    public TimetableGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
