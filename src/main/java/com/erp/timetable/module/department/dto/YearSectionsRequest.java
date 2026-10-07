package com.erp.timetable.module.department.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class YearSectionsRequest {
    private String yearLabel;
    private Boolean enabled;
    private List<String> sections;
}
