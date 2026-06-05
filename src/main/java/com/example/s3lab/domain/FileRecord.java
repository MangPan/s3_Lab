package com.example.s3lab.domain;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;

@Getter
public class FileRecord {
    private final String id;
    private final String bucket;
    private final String key;
    private final String originalFilename;
    private final String requestedContentType;

    private FileStatus status;
    private Long size;
    private String actualContentType;
    private String rejectedReason;

    private final Instant createdAt;
    private Instant uploadedAt;
    private Instant expiredAt;
    private Instant deletedAt;

    public FileRecord(
        String bucket,
        String key,
        String originalFilename,
        String requestedContentType
    ){
        this.id = UUID.randomUUID().toString();
        this.bucket = bucket;
        this.key = key;
        this.originalFilename = originalFilename;
        this.requestedContentType = requestedContentType;
        this.status = FileStatus.PENDING; // status는 생성시 PENDING으로 초기화
        this.createdAt = Instant.now();
    }

    public void complete(long size, String actualContentType){
        this.status = FileStatus.UPLOADED; // 최종 업로드 완료 확인시 status를 UPLOADED로 변경
        this.size = size;
        this.actualContentType = actualContentType;
        this.uploadedAt = Instant.now();
    }

    public void reject(String reason){
        this.status = FileStatus.REJECTED;
        this.rejectedReason = reason;
    }

    public void expire(){
        this.status = FileStatus.EXPIRED;
        this.expiredAt = Instant.now();
    }

    public void delete(){
        this.status = FileStatus.DELETED;
        this.deletedAt = Instant.now();
    }

    public boolean isPending(){
        return this.status == FileStatus.PENDING;
    }

    public boolean isPendingExpired(Instant now, long expirationSeconds){
        return status == FileStatus.PENDING
            && createdAt.plusSeconds(expirationSeconds).isBefore(now);
    }
}
