package com.example.s3lab.file.dto;

/*
파일 목록 응답용 DTO
이름(ID, KEY 여하간 식별자), 크기, 마지막 수정일
*/

public record FileObjectResponse(
    String key,
    long size,
    String lastModified
) {
}