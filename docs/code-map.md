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

Presigned PUT 업로드 흐름에서는 파일 상태를 추적하기 위해 `FileRecordRepository`와 `FileRecord`가 함께 사용됩니다.

```text
Presigned PUT URL 발급
-> FileRecord 생성
-> PENDING 상태 저장
-> 클라이언트가 S3에 직접 업로드
-> complete API 호출
-> HeadObject로 실제 업로드 확인
-> UPLOADED 상태 변경
```

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
- `createdAt`: 기록 생성 시각
- `uploadedAt`: 업로드 완료 시각

처음 생성될 때 상태는 `PENDING`입니다.

`complete` 메서드가 호출되면 상태가 `UPLOADED`로 바뀌고, 실제 파일 크기와 Content-Type이 저장됩니다.

## `domain/FileStatus.java`

파일 업로드 상태를 나타내는 enum입니다.

상태:

- `PENDING`: Presigned PUT URL은 발급됐지만 아직 업로드 완료 검증 전
- `UPLOADED`: S3에 객체가 존재함을 확인한 상태

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
-> FileRecord.complete
-> FileRecordResponse 반환
```

## `file/repository/FileRecordRepository.java`

파일 기록을 저장하는 인메모리 저장소입니다.

내부적으로 `ConcurrentHashMap`을 사용합니다.

제공하는 메서드:

- `save`
- `findById`
- `findAll`

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
- `createdAt`
- `uploadedAt`

