package com.example.s3lab.domain;

public enum FileStatus{
    PENDING, // URL 발급됨, 아직 업로드 완료 검증 전
    UPLOADED, // S3 Object 검증 완료
    REJECTED, // 업로드됐지만 정책 위반
    EXPIRED, // 제한 시간 안에 완료되지 않음
    DELETED // 삭제됨
}