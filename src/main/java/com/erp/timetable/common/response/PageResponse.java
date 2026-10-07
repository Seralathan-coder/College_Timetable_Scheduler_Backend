package com.erp.timetable.common.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Paginated response wrapper for list endpoints.
 */
@Getter
@Builder
public class PageResponse<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final boolean last;
    private final boolean first;
}
