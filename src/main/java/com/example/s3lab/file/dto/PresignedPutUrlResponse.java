package com.example.s3lab.file.dto;

public record PresignedPutUrlResponse(
    String key,
    String url,
    String method,
    long expiresInSeconds,
    String contentType
) {
}