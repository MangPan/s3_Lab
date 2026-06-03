package com.example.s3lab.file.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.s3lab.file.dto.FileObjectResponse;
import com.example.s3lab.file.dto.FileUploadResponse;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class FileService {

    private final S3Client s3Client; // S3Config에서 생성(등록)한 Bean이 스프링에 의해 주입됨 
    private final String bucket; // application.yml or properties에 설정된 버킷 이름

    public FileService(
            S3Client s3Client,
            @Value("${s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    /**
     * 파일 업로드
     * MultipartFile 형식의 파일을 받아 S3용 고유 키(경로)를 생성한 후 파일을 업로드
     */
    public FileUploadResponse upload(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        String key = generateKey(originalFilename); // 중복 방지를 위한 고유한 s3 key 생성

        try {
            // AWS SDK에서 요구하는 파일 메타데이터(bucket, key, file_type, file_size) 빌드
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            // S3Client를 통해 실제 파일 스트림 데이터를 전송하여 업로드 수행
            s3Client.putObject(
                    request,
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            // 업로드 완료 후 클라이언트에게 응답할 DTO 생성 및 반환
            return new FileUploadResponse(
                    bucket,
                    key,
                    originalFilename,
                    file.getSize(),
                    file.getContentType());
        } catch (IOException exception) {
            // 파일 스트림을 읽는 과정에서 예외 발생 시 커스텀 예외 전환 처리
            throw new RuntimeException("File Upload Error");
        }
    }

    /**
     * 버킷 내 파일 목록 조회
     * 현재 지정된 S3 버킷에 저장된 모든 오브젝트(파일)의 목록을 가져와 FileObjectResponse DTO 리스트로 변환
     */
    public List<FileObjectResponse> list() {
        // 목록 조회를 위한 대상 버킷 지정 request 빌드
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .build();
        
        // S3 서버로부터 오브젝트 목록 응답 수신
        ListObjectsV2Response response = s3Client.listObjectsV2(request);

        // S3 오브젝트 목록을 스트림을 통해 순회하며 가공 후 List 형식으로 변환 후 반환
        return response.contents()
                .stream()
                .map(object -> new FileObjectResponse(
                        object.key(),
                        object.size(),
                        object.lastModified().toString()))
                .toList();
    }

    /**
     * 파일 다운로드
     * 고유 Key를 기반으로 S3 서버에서 파일 데이터를 바이트 배열(ResponseBytes) 형태로 실시간 다운로드
     */
    public ResponseBytes<GetObjectResponse> download(String key) {
        // 다운로드할 특정 오브젝트의 버킷과 키 지정 request 빌드
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        // 파일을 메모리 상의 바이트 형태로 안전하게 긁어와 반환
        return s3Client.getObjectAsBytes(request);
    }

    /**
     * 파일 삭제
     * 고유 Key를 기반으로 S3 버킷 내에 존재하는 특정 오브젝트를 영구 삭제함 
     */
    public void delete(String key) {
        // 삭제할 특정 오브젝트의 버킷과 키 지정 request 빌드
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        // S3Client에 삭제 명령 전달
        s3Client.deleteObject(request);
    }


    /**
     * S3 저장용 고유 키 생성 메서드
     * 파일명 중복으로 인해 기존 파일이 덮어씌워지는 현상을 방지하기 위해 UUID와 폴더 구조를 조합한다.
     * -[출력 예시]-
     * 원본 파일이름이 'test.txt'라면
     * -> "uploads/ec8c899a-70eb-4015-a1b6-ad27a3da7c60-test.txt"
     */
    private String generateKey(String originalFilename) {
        String safeFilename = originalFilename == null ? "unknown" : originalFilename;

        return "uploads/" + UUID.randomUUID() + "-" + safeFilename;

        /* 
         * UUID란 컴퓨터 시스템에서 전 세계적으로 고유한 정보를 식별하기 위해 사용하는 128비트 숫자임
         * 중앙 시스템 발급 과정 없이 독립적으로 생성해도 중복될 확률이 극히 낮아 데이터베이스 기본키, 세션 ID 등에도 쓰임
         * 8-4-4-4-12 패턴의 32자리 16진수 문자열 형식을 가짐
         * 약 3.4 * 10^38 경우의 수라 사실상 유일하다고 봐도 됨
         */
    }

}
