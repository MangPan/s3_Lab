package com.example.s3lab.global.exception;

import java.util.List;

public record ErrorResponse(
    String code,
    String message,
    List<FieldErrorResponse> errors
) {

    public static ErrorResponse of(String code, String message){
        return new ErrorResponse(code, message, List.of());
    }

    public static ErrorResponse of(
        String code,
        String message,
        List<FieldErrorResponse> errors
    ){
        return new ErrorResponse(code, message, errors);
    }
}