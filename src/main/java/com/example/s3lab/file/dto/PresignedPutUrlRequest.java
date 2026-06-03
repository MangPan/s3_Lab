package com.example.s3lab.file.dto;

import jakarta.validation.constraints.NotBlank;

public record PresignedPutUrlRequest(
    @NotBlank(message = "file name is required.")
    String filename,

    @NotBlank(message = "Content-Type is required.")
    String contentType
) {
}