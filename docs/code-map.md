# 코드 구조 설명

이 문서는 주요 파일의 역할을 정리합니다.

## 전체 흐름

```text
HTTP 요청
-> FileController
-> FileService
-> S3Client 또는 S3Presigner
-> MinIO
```

예외가 발생하면 `GlobalExceptionHandler`가 공통 에러 응답 형식으로 변환합니다.

Presigned PUT 업로드 흐름에서는 파일 상태를 추적하기 위해 `FileRecordRepository`와 `FileRecord`가 함께 사용됩니다.

```text
Presigned PUT URL 발급
-> FileRecord 생성
-> PENDING 상태 저장
-> 클라이언트가 S3에 직접 업로드
-> complete API 호출
-> HeadObject로 실제 업로드 확인
-> 파일 크기와 Content-Type 검증
-> UPLOADED 상태 변경
```

제한 시간 안에 complete 처리되지 않은 `PENDING` 기록은 만료 정리 API로 `EXPIRED` 상태가 됩니다. 이때 S3 객체가 이미 올라와 있으면 함께 삭제합니다.

## `S3labApplication.java`

Spring Boot 애플리케이션의 시작점입니다.

`main` 메서드에서 `SpringApplication.run`을 호출해 애플리케이션을 실행합니다.

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

Presigned PUT 방식의 업로드 상태를 추적하는 도메인 객체입니다.

주요 필드:

- `id`: 파일 기록 ID
- `bucket`: 저장 대상 버킷
- `key`: S3 object key
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

파일 다운로드 API에서는 S3 key에서 파일명을 추출하고, `Content-Disposition` 헤더를 구성합니다.

## `file/service/FileService.java`

파일 처리의 핵심 로직이 들어 있는 서비스 클래스입니다.

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
-> repository 저장
-> PutObjectPresignRequest 생성
-> S3Presigner.presignPutObject
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
-> FileRecordResponse 반환
```

파일 크기나 Content-Type 검증에 실패하면 파일 상태가 `REJECTED`로 변경됩니다.

S3 객체가 아직 없으면 `S3_OBJECT_NOT_FOUND` 예외가 발생하고, 상태는 `PENDING`으로 유지됩니다.

### PENDING 파일 만료

```text
expirePendingFiles 호출
-> PENDING 상태 기록 조회
-> 생성 후 10초 초과 여부 확인
-> S3 객체가 존재하면 deleteObject
-> FileRecord.expire
-> ExpiredFileResponse 반환
```

### fileId 기반 삭제

```text
fileId
-> FileRecord 조회
-> 이미 DELETED면 바로 종료
-> S3Client.deleteObject
-> FileRecord.delete
```

## `file/repository/FileRecordRepository.java`

파일 기록을 저장하는 인메모리 저장소입니다.

내부적으로 `ConcurrentHashMap`을 사용합니다.

제공하는 메서드:

- `save`
- `findById`
- `findAll`
- `findByStatus`

주의할 점:

- 데이터베이스가 아니라 메모리 저장소입니다.
- 애플리케이션을 재시작하면 저장된 파일 기록은 사라집니다.
- S3나 MinIO에 저장된 실제 파일은 사라지지 않습니다.

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
