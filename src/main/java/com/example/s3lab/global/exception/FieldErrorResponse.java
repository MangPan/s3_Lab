package com.example.s3lab.global.exception;

public record FieldErrorResponse(
    String field,
    String message
) {
}