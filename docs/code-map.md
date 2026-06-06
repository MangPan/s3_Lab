# 코드 구조 설명

이 문서는 주요 파일의 역할을 정리합니다.

## 전체 흐름

```text
HTTP 요청
-> FileController
-> FileService
-> FileRecordRepository 또는 S3Client/S3Presigner
-> H2 Database 또는 MinIO
```

예외가 발생하면 `GlobalExceptionHandler`가 공통 에러 응답 형식으로 변환합니다.

Presigned PUT 업로드 흐름에서는 파일 상태를 추적하기 위해 `FileRecordRepository`와 `FileRecord`가 함께 사용됩니다.

```text
Presigned PUT URL 발급
-> FileRecord 생성
-> JPA Repository로 PENDING 상태 저장
-> 클라이언트가 S3에 직접 업로드
-> complete API 호출
-> HeadObject로 실제 업로드 확인
-> 파일 크기와 Content-Type 검증
-> UPLOADED 상태 변경
```

제한 시간 안에 complete 처리되지 않은 `PENDING` 기록은 만료 정리 API 또는 스케줄러로 `EXPIRED` 상태가 됩니다. 이때 S3 객체가 이미 올라와 있으면 함께 삭제합니다.

## `S3labApplication.java`

Spring Boot 애플리케이션의 시작점입니다.

`main` 메서드에서 `SpringApplication.run`을 호출해 애플리케이션을 실행합니다.

`@EnableScheduling`으로 스케줄링 기능을 켭니다. 이 설정이 있어야 `FileCleanupScheduler`의 `@Scheduled` 메서드가 주기적으로 실행됩니다.

## `config/S3Config.java`

S3 관련 Bean을 생성하는 설정 클래스입니다.

생성하는 Bean:

- `S3Client`
- `S3Presigner`

### `S3Client`

실제 S3 API 요청을 보내는 클라이언트입니다.

사용되는 작업:

- `putObject`
- `listObjectsV2`
- `getObjectAsBytes`
- `deleteObject`
- `headObject`

설정 특징:

- `endpointOverride`로 로컬 MinIO 주소 사용
- `StaticCredentialsProvider`로 access key, secret key 설정
- `forcePathStyle(true)`로 path-style access 사용
- API call timeout, attempt timeout 설정
- 표준 retry strategy 설정

### `S3Presigner`

Presigned URL을 생성하는 객체입니다.

사용되는 작업:

- `presignGetObject`
- `presignPutObject`

Presigned URL 생성은 S3에 실제 요청을 보내는 작업이 아니라, 서버가 가진 인증 정보로 서명된 URL 문자열을 만드는 작업입니다.

## `domain/FileRecord.java`

Presigned PUT 방식의 업로드 상태를 추적하는 JPA Entity입니다.

JPA 관련 설정:

- `@Entity`: JPA가 관리하는 테이블 매핑 대상
- `@Table(name = "file_records")`: 테이블 이름 지정
- `@Index(name = "idx_file_records_status_created_at", columnList = "status, createdAt")`: 만료 대상 조회에 쓰는 상태와 생성 시각 인덱스
- `@Index(name = "idx_file_records_object_key", columnList = "objectKey", unique = true)`: S3 object key 중복 방지
- `@Id`: 파일 기록 ID를 기본키로 사용
- `@Enumerated(EnumType.STRING)`: enum 이름을 문자열로 저장
- `protected FileRecord(){}`: JPA가 Entity를 생성할 때 사용하는 기본 생성자

주요 필드:

- `id`: 파일 기록 ID
- `bucket`: 저장 대상 버킷
- `objectKey`: S3 object key
- `originalFilename`: 원본 파일명
- `requestedContentType`: 클라이언트가 요청한 Content-Type
- `status`: 업로드 상태
- `size`: S3에 실제 업로드된 파일 크기
- `actualContentType`: S3가 가진 실제 Content-Type
- `rejectedReason`: 파일 정책 검증에 실패해 거부된 이유
- `createdAt`: 기록 생성 시각
- `uploadedAt`: 업로드 완료 시각
- `expiredAt`: 만료 처리 시각
- `deletedAt`: 삭제 처리 시각

처음 생성될 때 상태는 `PENDING`입니다.

`complete` 메서드가 호출되면 상태가 `UPLOADED`로 바뀌고, 실제 파일 크기와 Content-Type이 저장됩니다.

`reject` 메서드가 호출되면 상태가 `REJECTED`로 바뀌고 거부 이유가 저장됩니다.

`expire` 메서드가 호출되면 상태가 `EXPIRED`로 바뀌고 만료 시각이 저장됩니다.

`delete` 메서드가 호출되면 상태가 `DELETED`로 바뀌고 삭제 시각이 저장됩니다.

`getKey` 메서드는 기존 서비스 코드와 응답 DTO에서 `key`라는 이름을 계속 쓰기 위한 호환용 메서드입니다. 실제 Entity 필드명은 `objectKey`입니다.

## `domain/FileStatus.java`

파일 업로드 상태를 나타내는 enum입니다.

상태:

- `PENDING`: Presigned PUT URL은 발급됐지만 아직 업로드 완료 검증 전
- `UPLOADED`: S3에 객체가 존재함을 확인한 상태
- `REJECTED`: 업로드된 객체가 파일 크기 또는 Content-Type 정책을 통과하지 못한 상태
- `EXPIRED`: 제한 시간 안에 완료 처리되지 않은 상태
- `DELETED`: 파일 삭제가 처리된 상태

## `file/controller/FileController.java`

HTTP 요청을 받는 REST Controller입니다.

기본 경로:

```text
/files
```

Controller는 요청 파라미터, path variable, request body를 받아 `FileService`에 위임합니다.

주요 역할:

- Multipart 파일 업로드 요청 받기
- S3 객체 목록 조회 요청 받기
- 파일 다운로드 응답 구성
- 파일 삭제 요청 받기
- Presigned URL 발급 요청 받기
- 업로드 완료 검증 요청 받기
- 파일 기록 목록 조회 요청 받기
- fileId 기반 삭제 요청 받기
- 만료된 PENDING 파일 수동 정리 요청 받기

파일 다운로드 API에서는 S3 key에서 파일명을 추출하고, `Content-Disposition` 헤더를 구성합니다.

## `file/service/FileService.java`

파일 처리의 핵심 로직이 들어 있는 서비스 클래스입니다.

클래스에는 `@Transactional(readOnly = true)`가 붙어 있어 기본 조회 작업은 읽기 전용 트랜잭션으로 실행됩니다. 상태를 변경하는 메서드에는 별도로 `@Transactional`을 붙여 JPA 변경 감지가 동작하도록 합니다.

`LoggerFactory.getLogger(FileService.class)`로 SLF4J logger를 생성하고, 파일 상태 변경과 S3 정리 결과를 기록합니다.

주요 역할:

- S3 key 생성
- Multipart 파일 업로드
- S3 객체 목록 조회
- S3 객체 다운로드
- S3 객체 삭제
- Presigned GET URL 생성
- Presigned PUT URL 생성
- 업로드 기록 생성
- 업로드 완료 검증
- 파일 기록 목록 조회
- fileId 기반 다운로드 URL 발급
- fileId 기반 삭제
- 만료된 PENDING 파일 정리
- 요청 Content-Type 검증
- 업로드된 객체의 크기와 Content-Type 검증

### 일반 업로드

```text
MultipartFile
-> generateKey
-> PutObjectRequest 생성
-> S3Client.putObject
-> FileUploadResponse 반환
```

### 일반 다운로드

```text
key
-> GetObjectRequest 생성
-> S3Client.getObjectAsBytes
-> byte[] 응답
```

### Presigned PUT URL 발급

```text
filename, contentType
-> validateRequestedContentType
-> generateKey
-> FileRecord 생성
-> FileRecordRepository.save
-> PutObjectPresignRequest 생성
-> S3Presigner.presignPutObject
-> 발급 성공 로그 기록
-> PresignedPutUrlResponse 반환
```

### 업로드 완료 검증

```text
fileId
-> FileRecord 조회
-> HeadObjectRequest 생성
-> S3Client.headObject
-> validateUploadedObject
-> FileRecord.complete
-> 완료 성공 로그 기록
-> FileRecordResponse 반환
```

파일 크기나 Content-Type 검증에 실패하면 파일 상태가 `REJECTED`로 변경됩니다.

S3 객체가 아직 없으면 `S3_OBJECT_NOT_FOUND` 예외가 발생하고, 상태는 `PENDING`으로 유지됩니다.

### PENDING 파일 만료

```text
expirePendingFiles 호출
-> cutoff = now - file.pending-expiration-seconds
-> PageRequest.of(0, 100)
-> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc
-> S3 객체가 존재하면 deleteObject
-> FileRecord.expire
-> 만료 처리 로그 기록
-> ExpiredFileResponse 반환
```

### fileId 기반 삭제

```text
fileId
-> FileRecord 조회
-> 이미 DELETED면 바로 종료
-> S3Client.deleteObject
-> FileRecord.delete
-> 삭제 로그 기록
```

## `file/repository/FileRecordRepository.java`

파일 기록을 저장하고 조회하는 Spring Data JPA Repository입니다.

`JpaRepository<FileRecord, String>`을 상속하므로 기본 CRUD 메서드를 자동으로 사용할 수 있습니다.

제공하는 메서드:

- `save`
- `findById`
- `findAll`
- `findByStatusOrderByCreatedAtDesc`
- `findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc`

파생 쿼리 메서드:

- `findByStatusOrderByCreatedAtDesc`: 특정 상태의 파일 기록을 최신순으로 조회합니다.
- `findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc`: 특정 상태이면서 생성 시각이 기준보다 오래된 기록을 오래된 순으로 조회합니다.

`Pageable`을 함께 넘기면 한 번에 처리할 개수를 제한할 수 있습니다. 만료 정리에서는 `PageRequest.of(0, 100)`으로 최대 100개씩 처리합니다.

현재 DB 설정:

- H2 인메모리 데이터베이스 사용
- `spring.jpa.hibernate.ddl-auto=create-drop`
- 애플리케이션 시작 시 테이블 생성, 종료 시 테이블 삭제
- S3나 MinIO에 저장된 실제 파일은 DB 재생성과 별개로 남아 있습니다.

## `file/scheduler/FileCleanupScheduler.java`

완료되지 않은 `PENDING` 파일을 자동으로 정리하는 스케줄러입니다.

주요 설정:

- `@Component`: Spring Bean 등록
- `@RequiredArgsConstructor`: `FileService` 생성자 주입
- `@Scheduled(fixedDelayString = "${file.expire-schedule-ms}")`: 설정값 주기로 반복 실행

동작 흐름:

```text
스케줄러 실행
-> FileService.expirePendingFiles
-> 만료된 파일이 있으면 count 로그 출력
-> 예외 발생 시 실패 로그 출력
```

현재 `file.expire-schedule-ms=10000`이므로 이전 실행이 끝난 뒤 10초가 지나면 다시 실행됩니다.

## 로깅

이 프로젝트는 SLF4J `Logger`와 `LoggerFactory`를 사용합니다.

로그가 남는 주요 지점:

- Presigned PUT URL 발급: `Created presigned PUT URL`
- 업로드 완료 검증 성공: `Completed file upload`
- S3 객체 미존재: `S3 object not found while completing upload`
- 파일 정책 위반: `Rejected uploaded file`
- 삭제 완료 또는 이미 삭제된 파일: `Deleted file`, `File already deleted`
- PENDING 파일 만료: `Expired pending file`
- 만료 대상 S3 객체가 이미 없음: `S3 object already missing`
- S3 객체 삭제 실패: `Failed to delete S3 object`
- 스케줄러 정리 결과: `Expired pending files`

`application.properties`에서 애플리케이션 로그는 DEBUG 이상, AWS SDK 내부 로그는 WARN 이상으로 제한합니다.

## DTO

DTO는 API 요청과 응답 형태를 표현합니다.

### `FileUploadResponse`

Multipart 업로드 성공 시 반환합니다.

필드:

- `bucket`
- `key`
- `originalFilename`
- `size`
- `contentType`

### `FileObjectResponse`

S3 객체 목록 조회 응답에 사용합니다.

필드:

- `key`
- `size`
- `lastModified`

### `PresignedGetUrlResponse`

다운로드용 Presigned URL 응답에 사용합니다.

필드:

- `key`
- `url`
- `method`
- `expressInSeconds`

### `PresignedPutUrlRequest`

업로드용 Presigned URL 발급 요청에 사용합니다.

필드:

- `filename`
- `contentType`

두 필드 모두 `@NotBlank` 검증이 적용되어 있습니다.

### `PresignedPutUrlResponse`

업로드용 Presigned URL 발급 응답에 사용합니다.

필드:

- `fileId`
- `key`
- `url`
- `method`
- `expiresInSeconds`
- `contentType`

### `FileRecordResponse`

파일 기록 조회와 업로드 완료 검증 응답에 사용합니다.

필드:

- `id`
- `bucket`
- `key`
- `originalFilename`
- `requestedContentType`
- `status`
- `size`
- `actualContentType`
- `rejectedReason`
- `createdAt`
- `uploadedAt`
- `expiredAt`
- `deletedAt`

### `ExpiredFileResponse`

만료된 PENDING 파일 정리 결과에 사용합니다.

필드:

- `fileId`
- `key`
- `objectDeleted`: 만료 처리 중 S3 객체를 실제 삭제했는지 여부

## `global/exception`

전역 예외 처리와 공통 에러 응답을 담당하는 패키지입니다.

### `BadRequestException`

잘못된 요청 값을 표현하는 예외입니다.

예시:

- 허용되지 않는 Content-Type
- 파일 크기 제한 초과
- 요청 Content-Type과 실제 업로드 Content-Type 불일치

HTTP 응답 상태는 `400 Bad Request`입니다.

### `NotFoundException`

요청한 리소스를 찾지 못했을 때 사용하는 예외입니다.

현재는 파일 기록 ID를 찾지 못했을 때 사용합니다.

HTTP 응답 상태는 `404 Not Found`입니다.

### `ConflictException`

요청 자체는 이해했지만 현재 리소스 상태와 충돌할 때 사용하는 예외입니다.

예시:

- 아직 S3에 업로드되지 않은 파일 완료 처리
- 삭제된 파일 완료 처리
- 거부 상태 파일 완료 처리
- 업로드 완료 전 다운로드 URL 요청

HTTP 응답 상태는 `409 Conflict`입니다.

### `GlobalExceptionHandler`

`@RestControllerAdvice`로 등록된 전역 예외 처리기입니다.

처리하는 예외:

- `BadRequestException`
- `NotFoundException`
- `ConflictException`
- `MethodArgumentNotValidException`
- 기타 `Exception`

### `ErrorResponse`

공통 에러 응답 DTO입니다.

필드:

- `node`: 에러 코드
- `message`: 에러 메시지
- `errors`: 필드 단위 검증 오류 목록

### `FieldErrorResponse`

필드 단위 검증 실패를 표현하는 DTO입니다.

필드:

- `field`
- `message`
