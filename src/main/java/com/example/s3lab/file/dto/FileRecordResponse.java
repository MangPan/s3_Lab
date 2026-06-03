package com.example.s3lab.file.dto;

import java.time.Instant;

import com.example.s3lab.domain.FileRecord;
import com.example.s3lab.domain.FileStatus;

public record FileRecordResponse(
        String id,
        String bucket,
        String key,
        String originalFilename,
        String requestedContentType,
        FileStatus status,
        Long size,
        String actualContentType,
        String failedReason,
        Instant createdAt,
        Instant uploadedAt,
        Instant deletedAt
    ) {

    public static FileRecordResponse from(FileRecord fileRecord){
        return new FileRecordResponse(
            fileRecord.getId(),
            fileRecord.getBucket(),
            fileRecord.getKey(),
            fileRecord.getOriginalFilename(),
            fileRecord.getRequestedContentType(),
            fileRecord.getStatus(),
            fileRecord.getSize(),
            fileRecord.getActualContentType(),
            fileRecord.getFailedReason(),
            fileRecord.getCreatedAt(),
            fileRecord.getUploadedAt(),
            fileRecord.getDeletedAt()
        );
    }
}