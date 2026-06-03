package com.example.s3lab.file.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.s3lab.domain.FileRecord;
import com.example.s3lab.domain.FileStatus;
import com.example.s3lab.file.dto.FileObjectResponse;
import com.example.s3lab.file.dto.FileRecordResponse;
import com.example.s3lab.file.dto.FileUploadResponse;
import com.example.s3lab.file.dto.PresignedGetUrlResponse;
import com.example.s3lab.file.dto.PresignedPutUrlRequest;
import com.example.s3lab.file.dto.PresignedPutUrlResponse;
import com.example.s3lab.file.repository.FileRecordRepository;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.s3lab.global.exception.BadRequestException;
import com.example.s3lab.global.exception.ConflictException;
import com.example.s3lab.global.exception.NotFoundException;

import org.springframework.beans.factory.annotation.Value;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class FileService {

    private static final long MAX_FILE_SIZE = 5*1024*1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
        "image/jpeg",
        "image/png",
        "image/webp",
        "text/plain"
    );

    private final S3Client s3Client; // S3Config에서 생성(등록)한 Bean이 스프링에 의해 주입됨
    private final S3Presigner s3Presigner;
    private final FileRecordRepository fileRecordRepository;
    private final String bucket; // application.yml or properties에 설정된 버킷 이름

    public FileService(
            S3Client s3Client,
            S3Presigner s3Presigner,
            FileRecordRepository fileRecordRepository,
            @Value("${s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.fileRecordRepository = fileRecordRepository;
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
    public PresignedGetUrlResponse createPresignedGetUrl(String key) {
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
                600);
    }

    /**
     * 업로드용 Presigned URL 생성 (PUT)
     * 프론트엔드가 스프링 백엔드 서버에 무거운 대용량 파일 바이트를 전송하지 않고,
     * S3 스토리지로 직접 파일을 안전하게 바로 업로드(PUT) 할 수 있는 임시 주소를 발급한다.
     */
    public PresignedPutUrlResponse createPresignedPutUrl(PresignedPutUrlRequest request) {

        // 먼저 Content-Type 유효성 검사 수행
        validateRequestedContentType(request.contentType());

        // 업로드될 파일의 유일한 경로 이름(S3 Key)를 먼저 선행 발급
        String key = generateKey(request.filename());

        // 파일 상태(PENDING or UPLOADED) 추적을 위한 도메인 엔티티 객체 생성(기본 : PENDING)
        FileRecord fileRecord = new FileRecord(
                this.bucket,
                key,
                request.filename(),
                request.contentType());
        
        // 파일 검증 전 단계 상태 저장
        fileRecordRepository.save(fileRecord);

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

        // 이후 완료 검증 프로세스에서 추적할 수 있도록 파일의 고유파일 ID값과 함께 응답 DTO 반환
        return new PresignedPutUrlResponse(
                fileRecord.getId(),
                key,
                url,
                "PUT",
                600,
                request.contentType());
    }

    /**
     * Presigned URL 업로드 완료 검증 및 상태 확정
     * 프론트엔드가 S3로 직접 파일 전송을 마친 후 업로드가 완료 되었다고 호출하는 완료 시그널 API
     * S3 서버에 파일 메타데이터를(HeadObject)를 조회해보고, 실존함이 확인되면 해당 파일 상태를 'UPLOADED'로 최종 전환함
     */
    public FileRecordResponse completeUpload(String fileId){
        // 전달받은 고유 ID로 데이터베이스에 저장된 파일 기록 조회
        FileRecord fileRecord = getFileRecordOrThrow(fileId);

        // 멱등성 보장: 이미 업로드 완료 처리된 파일이라면 검증을 스킵하고 즉시 결과를 반환함
        if(fileRecord.getStatus() == FileStatus.UPLOADED){
            return FileRecordResponse.from(fileRecord);
        }

        // 삭제된 파일이라면 예외 던짐
        if(fileRecord.getStatus() == FileStatus.DELETED){
            throw new ConflictException(
                "DELETED_FILE_CANNOT_COMPLETE",
                "삭제된 파일은 완료 처리할 수 없습니다. fileId=" + fileId
            );
        }
        // 실패 처리된 파일이라면 예외 던짐
        if(fileRecord.getStatus() == FileStatus.FAILED){
            throw new ConflictException(
                "FAILED_FILE_CANNOT_COMPLETE",
                "실패 처리된 파일은 완료 처리할 수 없습니다. fileId=" + fileId
            );
        }

        try{
            // 파일의 모든 바이트를 직접 내려받지 않고, 파일의 헤더 정도(존재 여부, 크기 등)만 경량 조회하는 request 빌드
            HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(fileRecord.getBucket())
                .key(fileRecord.getKey())
                .build();
            
            // S3 통신 수행: 파일이 없다면 이 시점에 NoSuchKeyException 예외 터짐
            HeadObjectResponse response = s3Client.headObject(request);

            validateUploadedObject(fileRecord, response);

            // 파일 실존 확인 성공시 S3가 반환한 실제 파일 크기와 컨텐츠 타입으로 도메인 객체 데이터 갱신 및 상태값 UPLOADED 변경
            fileRecord.complete(
                response.contentLength(), 
                response.contentType()
            );

            // dto로 반환
            return FileRecordResponse.from(fileRecord);
        }
        catch(NoSuchKeyException exception){
            fileRecord.fail("S3 object not found");

            throw new ConflictException(
                "S3_OBJECT_NOT_FOUND",
                "아직 S3에 파일이 업로드되지 않았습니다. key=" + fileRecord.getKey()
            );
        }
        catch (BadRequestException exception){
            fileRecord.fail(exception.getMessage());
            throw exception;
        }
        catch(RuntimeException exception){
            if(fileRecord.getStatus() == FileStatus.PENDING){
                fileRecord.fail(exception.getMessage());
            }
            throw exception;
        }
    }

    /**
     * 데이터베이스 내 전체 파일 기록 리스트 조회
     * S3 스토리지 내부를 뒤지는 대신 DB 인덱스를 통해 가볍게 전체 파일 이력 메타데이터 목록을 가져옴
     */
    public List<FileRecordResponse> listRecords(){
        return fileRecordRepository.findAll()
            .stream()
            .map(FileRecordResponse::from)
            .toList();
    }

    /**
     * 고유 DB 식별값(FileId)를 통한 다운로드 Presigned URL 생성
     * S3 Key 주소를 몰라도 FileId로 다운로드 임시 주소 획득 가능케 함
     */
    public PresignedGetUrlResponse createPresignedGetUrlByFileId(String fileId){
        FileRecord fileRecord = getFileRecordOrThrow(fileId);
        
        if(fileRecord.getStatus() != FileStatus.UPLOADED){
            throw new ConflictException(
                "FILE_NOT_UPLOADED",
                "업로드 완료된 파일만 다운로드 URL을 발급할 수 있습니다. status=" + fileRecord.getStatus()
            );
        }
        
        return createPresignedGetUrl(fileRecord.getKey());
    }

    public void deleteByFileId(String fileId){
        FileRecord fileRecord = getFileRecordOrThrow(fileId);

        if(fileRecord.getStatus() == FileStatus.DELETED){
            return;
        }

        DeleteObjectRequest request = DeleteObjectRequest.builder()
            .bucket(fileRecord.getBucket()) 
            .key(fileRecord.getKey())
            .build();
        
        s3Client.deleteObject(request);

        fileRecord.delete();
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

    private FileRecord getFileRecordOrThrow(String fileId){
        return fileRecordRepository.findById(fileId)
            .orElseThrow(
                () -> new NotFoundException(
                    "FILE_RECORD_NOT_FOUND",
                    "파일 기록을 찾을 수 없습니다. fileId=" + fileId 
                )
            );
    }

    private void validateRequestedContentType(String contentType){
        if(!ALLOWED_CONTENT_TYPES.contains(contentType)){
            throw new BadRequestException(
                "INVALID_CONTENT_TYPE",
                "허용되지 않는 Content-Type입니다. contentType=" + contentType
            );
        }
    }

    private void validateUploadedObject(FileRecord fileRecord, HeadObjectResponse response){
        if(response.contentLength() > MAX_FILE_SIZE){
            throw new BadRequestException(
                "FILE_SIZE_EXCEEDED",
                "파일 크기가 제한을 초과했습니다. size=" + response.contentLength()
            );
        }

        if(!ALLOWED_CONTENT_TYPES.contains(response.contentType())){
            throw new BadRequestException(
                "INVALID_UPLOADED_CONTENT_TYPE",
                "업로드된 파일의 Content-Type이 허용되지 않습니다. contentType" + response.contentType()
            );
        }

        if(!fileRecord.getRequestedContentType().equals(response.contentType())){
            throw new BadRequestException(
                "CONTENT_TYPE_MISMATCH",
                "요청 Content-Type과 실제 Content-Type이 다릅니다. requested=" 
                + fileRecord.getRequestedContentType()
                + ", actual="
                + response.contentType()
            );
        }
    }
}
