package com.example.s3lab.file.dto;

public record FileUploadResponse(
    String bucket,
    String key,
    String originalFilename,
    long size,
    String contentType
) {
}