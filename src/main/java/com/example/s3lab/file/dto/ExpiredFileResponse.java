package com.example.s3lab.file.dto;

public record ExpiredFileResponse(
    String fileId,
    String key,
    boolean objectDeleted
) {
}