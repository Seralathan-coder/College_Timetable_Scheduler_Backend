package com.erp.timetable.module.auth.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Tenant college information as exposed to clients.
 */
@Data
@Builder
public class CollegeResponse {

    private Long id;
    private String name;
    private String code;
    private String address;
    private String phone;
    private String email;
    private String adminUsername;
    private Long adminUserId;
}