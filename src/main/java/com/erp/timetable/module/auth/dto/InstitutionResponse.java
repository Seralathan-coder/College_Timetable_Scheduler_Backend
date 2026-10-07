package com.erp.timetable.module.auth.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Institution information as exposed to clients (id fixed at 1).
 */
@Data
@Builder
public class InstitutionResponse {

    private Long id;
    private String name;
    private String code;
    private String address;
    private String phone;
    private String email;
}