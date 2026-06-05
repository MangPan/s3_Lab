package com.example.s3lab.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(
    name = "file_records",
    indexes = {
        @Index(name = "idx_file_records_status_created_at", columnList = "status, createdAt"),
        @Index(name = "idx_file_records_object_key", columnList = "objectKey", unique = true)
    }
)
public class FileRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String bucket;

    @Column(nullable = false, unique = true, length = 1000)
    private String objectKey;

    @Column(nullable = false, length = 100)
    private String originalFilename;

    @Column(nullable = false, length = 100)
    private  String requestedContentType;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FileStatus status;

    private Long size;

    @Column(length = 100)
    private String actualContentType;

    @Column(columnDefinition = "text")
    private String rejectedReason;

    @Column(nullable = false, updatable = false)
    private  Instant createdAt;

    private Instant uploadedAt;
    private Instant expiredAt;
    private Instant deletedAt;

    protected FileRecord(){}
    public FileRecord(
        String bucket,
        String objectKey,
        String originalFilename,
        String requestedContentType
    ){
        this.id = UUID.randomUUID().toString();
        this.bucket = bucket;
        this.objectKey = objectKey;
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

    public String getKey(){ // 기존 코드 호환용
        return this.objectKey;
    }
}
