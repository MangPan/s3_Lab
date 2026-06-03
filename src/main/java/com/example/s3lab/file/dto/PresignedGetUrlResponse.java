package com.example.s3lab.file.dto;

public record PresignedGetUrlResponse(
    String key,
    String url,
    String method,
    long expressInSeconds
) {
}