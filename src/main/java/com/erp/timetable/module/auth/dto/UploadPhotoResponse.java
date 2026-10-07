package com.erp.timetable.module.auth.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Response payload for POST /me/photo — returns the URL path of the stored photo
 * (the DB stores only the path, never the file).
 */
@Data
@Builder
public class UploadPhotoResponse {

    private String profilePhotoUrl;
}