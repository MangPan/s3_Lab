package com.example.s3lab.file.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.s3lab.file.dto.FileObjectResponse;
import com.example.s3lab.file.dto.FileUploadResponse;
import com.example.s3lab.file.dto.PresignedGetUrlResponse;
import com.example.s3lab.file.dto.PresignedPutUrlRequest;
import com.example.s3lab.file.dto.PresignedPutUrlResponse;

import java.io.IOException;
import java.time.Duration;
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
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class FileService {

    private final S3Client s3Client; // S3Config에서 생성(등록)한 Bean이 스프링에 의해 주입됨 
    private final S3Presigner s3Presigner;
    private final String bucket; // application.yml or properties에 설정된 버킷 이름

    public FileService(
            S3Client s3Client,
            S3Presigner s3Presigner,
            @Value("${s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
    }

    // ====================================================================================================
    // FILE UPLOAD / DOWNLOAD METHODS
    // ====================================================================================================

    /**
     * 파일 업로드
     * MultipartFile 형식의 파일을 받아 S3용 고유 키(경로)를 생성한 후 파일을 업로드
     * 
     * S3Client는 표준 보안 규격인 Signature V4 방식을 사용한다.
     * s3Client의 메서드를 호출할 시 현재시간, 요청내용, 파일데이터를 취합한 후
     * 메모리에 보관 중인 SecretKey로 서명한 후
     * S3 서버로 단발성 전송한다.
     * 따라서 Jwt같은 토큰 인증 방식과 다르게 비밀번호나 고정 토큰이 오가지 않으므로 탈취 위험이 극히 낮고
     * 토큰 발급을 위한 사전 통신 과정이 없으므로 네트워크 오버헤드가 없다.
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



// ====================================================================================================
// PRESIGNED URL METHODS
// ====================================================================================================

    /**
     * 다운로드용 Presigned URL 생성 (GET)
     * 특정 key에 해당하는 비공개 파일에 대해, 
     * 스프링 서버를 거치지 않고 S3에서 안전하게 다운로드 할 수 있는 
     * 만료 시간이 있는 임시 URL을 발급한다.
     * 
     * S3Presigner는 S3와 통신을 하지 않는다.
     * Presigned URL은 스프링 서버가 자신의 권한을 담아 원격으로 서명해준 1회용 URL이다.
     * 따라서 서버와 아무런 통신을 하지 않고 Java단에서 서명만 해서 프론트엔드에 던짐
     */
    public PresignedGetUrlResponse createPresignedGetUrl(String key){
        // 어떤 오브젝트(버킷, 키)를 가져올 것인지 표준 s3 Get 요청서 작성
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build();
        
        // 만료 시간(10분) 정보와 위의 Get 요청서를 묶어 Presign 전용 요청서로 빌드
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(10))
            .getObjectRequest(getObjectRequest)
            .build();
        
        // S3Presigner를 통해 보안 서명(Signature)이 포함된 최종 임시 URL 문자열 추출
        String url = s3Presigner.presignGetObject(presignRequest)
            .url()
            .toString();

        // 정보를 DTO에 담아 반환
        return new PresignedGetUrlResponse(
            key,
            url,
            "GET",
            600
        );
    }

    /**
     * 업로드용 Presigned URL 생성 (PUT)
     * 프론트엔드가 스프링 백엔드 서버에 무거운 대용량 파일 바이트를 전송하지 않고,
     * S3 스토리지로 직접 파일을 안전하게 바로 업로드(PUT) 할 수 있는 임시 주소를 발급한다.
     */
    public PresignedPutUrlResponse createPresignedPutUrl(PresignedPutUrlRequest request){
        // 업로드될 파일의 유일한 경로 이름(S3 Key)를 먼저 선행 발급
        String key = generateKey(request.filename());

        // 어떤 메타데이터(bucket, key, content_type)로 파일을 업로드 허용할 것인지 Put 요청서 작성
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(request.contentType())
            .build();

        // 만료 시간(10분) 정보와 위의 Put 요청서를 묶어 Presign 전용 요청서로 빌드
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(10))
            .putObjectRequest(putObjectRequest)
            .build();

        // S3Presigner를 통해 프론트가 직접 HTTP PUT 메서드를 호출할 암호화 서명된 URL 추출
        String url = s3Presigner.presignPutObject(presignRequest)
            .url()
            .toString();

        // 발급된 Key 정보와 URL, 헤더 검증용 컨텐츠 타입 데이터를 DTO에 담아 반환
        return new PresignedPutUrlResponse(
            key,
            url,
            "PUT",
            600,
            request.contentType()
        );
    }


// ====================================================================================================
// PRIVATE HELPER METHODS
// ====================================================================================================


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
