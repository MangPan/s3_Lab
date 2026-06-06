# API 정리

이 문서는 `FileController`에 정의된 API를 기준으로 작성합니다.

기본 경로:

```text
/files
```

## 공통 에러 응답

전역 예외 처리는 `GlobalExceptionHandler`에서 담당합니다.

에러 응답 형식:

```json
{
  "node": "ERROR_CODE",
  "message": "에러 메시지",
  "errors": []
}
```

검증 실패처럼 필드 단위 오류가 있는 경우 `errors`에 필드명과 메시지가 들어갑니다.

```json
{
  "node": "VALIDATION_FAILED",
  "message": "요청 값 검증에 실패했습니다",
  "errors": [
    {
      "field": "filename",
      "message": "file name is required."
    }
  ]
}
```

주요 HTTP 상태:

| Status | 상황 |
| --- | --- |
| `400 Bad Request` | 요청 값 검증 실패, 허용되지 않은 Content-Type, 파일 크기 제한 초과 |
| `404 Not Found` | 파일 기록을 찾을 수 없음 |
| `409 Conflict` | 현재 파일 상태와 요청이 충돌함 |
| `500 Internal Server Error` | 처리하지 못한 서버 내부 오류 |

## 1. Multipart 파일 업로드

```http
POST /files
Content-Type: multipart/form-data
```

백엔드 서버가 파일을 받아 S3에 업로드합니다.

### Request

| 이름 | 위치 | 타입 | 설명 |
| --- | --- | --- | --- |
| `file` | form-data | file | 업로드할 파일 |

### curl

```bash
curl -X POST http://localhost:8080/files \
  -F "file=@./sample.txt"
```

### Response

```json
{
  "bucket": "s3-lab-bucket",
  "key": "uploads/uuid-sample.txt",
  "originalFilename": "sample.txt",
  "size": 1234,
  "contentType": "text/plain"
}
```

### 내부 흐름

```text
FileController.upload
-> FileService.upload
-> S3Client.putObject
```

## 2. S3 객체 목록 조회

```http
GET /files
```

현재 설정된 버킷의 객체 목록을 조회합니다.

### curl

```bash
curl http://localhost:8080/files
```

### Response

```json
[
  {
    "key": "uploads/uuid-sample.txt",
    "size": 1234,
    "lastModified": "2026-06-03T00:00:00Z"
  }
]
```

### 내부 흐름

```text
FileController.list
-> FileService.list
-> S3Client.listObjectsV2
```

## 3. 파일 다운로드

```http
GET /files/download?key={key}
```

S3 객체를 백엔드 서버가 내려받은 뒤 클라이언트에게 파일 바이너리로 응답합니다.

### Request

| 이름 | 위치 | 타입 | 설명 |
| --- | --- | --- | --- |
| `key` | query string | string | 다운로드할 S3 object key |

### curl

```bash
curl -OJ "http://localhost:8080/files/download?key=uploads/uuid-sample.txt"
```

### Response

파일 바이너리를 반환합니다.

응답 헤더에는 다운로드 파일명을 지정하기 위한 `Content-Disposition`이 포함됩니다.

### 내부 흐름

```text
FileController.download
-> FileService.download
-> S3Client.getObjectAsBytes
```

## 4. 파일 삭제

```http
DELETE /files?key={key}
```

S3 버킷에서 지정한 객체를 삭제합니다.

### Request

| 이름 | 위치 | 타입 | 설명 |
| --- | --- | --- | --- |
| `key` | query string | string | 삭제할 S3 object key |

### curl

```bash
curl -X DELETE "http://localhost:8080/files?key=uploads/uuid-sample.txt"
```

### Response

```http
204 No Content
```

### 내부 흐름

```text
FileController.delete
-> FileService.delete
-> S3Client.deleteObject
```

## 5. key 기반 Presigned GET URL 발급

```http
GET /files/presigned-get-url?key={key}
```

특정 S3 object key로 다운로드용 Presigned URL을 발급합니다.

### Request

| 이름 | 위치 | 타입 | 설명 |
| --- | --- | --- | --- |
| `key` | query string | string | 다운로드할 S3 object key |

### curl

```bash
curl "http://localhost:8080/files/presigned-get-url?key=uploads/uuid-sample.txt"
```

### Response

```json
{
  "key": "uploads/uuid-sample.txt",
  "url": "http://localhost:9000/s3-lab-bucket/uploads/uuid-sample.txt?...",
  "method": "GET",
  "expressInSeconds": 600
}
```

### 내부 흐름

```text
FileController.createPresignedGetUrl
-> FileService.createPresignedGetUrl
-> S3Presigner.presignGetObject
```

## 6. Presigned PUT URL 발급

```http
POST /files/presigned-put-url
Content-Type: application/json
```

클라이언트가 S3에 직접 업로드할 수 있는 PUT URL을 발급합니다.

이 API는 파일 바이트를 받지 않습니다. 파일명과 Content-Type만 받아 S3 key와 Presigned URL을 생성합니다.

요청한 Content-Type은 아래 값만 허용됩니다.

- `image/jpeg`
- `image/png`
- `image/webp`
- `text/plain`

### Request

```json
{
  "filename": "sample.txt",
  "contentType": "text/plain"
}
```

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| `filename` | string | 원본 파일명 |
| `contentType` | string | 업로드할 파일의 Content-Type |

### curl

```bash
curl -X POST http://localhost:8080/files/presigned-put-url \
  -H "Content-Type: application/json" \
  -d '{"filename":"sample.txt","contentType":"text/plain"}'
```

### Response

```json
{
  "fileId": "file-record-id",
  "key": "uploads/uuid-sample.txt",
  "url": "http://localhost:9000/s3-lab-bucket/uploads/uuid-sample.txt?...",
  "method": "PUT",
  "expiresInSeconds": 600,
  "contentType": "text/plain"
}
```

### 내부 흐름

```text
FileController.createPresignedPutUrl
-> FileService.createPresignedPutUrl
-> validateRequestedContentType
-> FileRecord 생성, 상태 PENDING
-> FileRecordRepository.save
-> S3Presigner.presignPutObject
```

### 실패 응답 예시

허용되지 않은 Content-Type을 요청하면 `400 Bad Request`가 반환됩니다.

```json
{
  "node": "INVALID_CONTENT_TYPE",
  "message": "허용되지 않는 Content-Type입니다. contentType=application/pdf",
  "errors": []
}
```

## 7. Presigned PUT 업로드 완료 검증

```http
POST /files/{fileId}/complete
```

Presigned PUT URL로 S3에 직접 업로드한 뒤, 백엔드에 업로드 완료를 알리는 API입니다.

백엔드는 S3에 `HeadObject` 요청을 보내 실제 객체가 존재하는지 확인합니다. 확인에 성공하면 파일 기록 상태를 `UPLOADED`로 변경합니다.

이미 `UPLOADED` 상태인 파일에 다시 호출하면 같은 파일 기록을 반환합니다.

완료 검증 시 아래 조건도 함께 확인합니다.

- S3 객체가 실제로 존재해야 합니다.
- 파일 크기는 5MB 이하여야 합니다.
- 업로드된 객체의 Content-Type이 허용 목록에 포함되어야 합니다.
- 요청 당시 Content-Type과 실제 업로드된 Content-Type이 같아야 합니다.

### Request

| 이름 | 위치 | 타입 | 설명 |
| --- | --- | --- | --- |
| `fileId` | path variable | string | Presigned PUT URL 발급 시 받은 파일 기록 ID |

### curl

```bash
curl -X POST http://localhost:8080/files/{fileId}/complete
```

### Response

```json
{
  "id": "file-record-id",
  "bucket": "s3-lab-bucket",
  "key": "uploads/uuid-sample.txt",
  "originalFilename": "sample.txt",
  "requestedContentType": "text/plain",
  "status": "UPLOADED",
  "size": 1234,
  "actualContentType": "text/plain",
  "rejectedReason": null,
  "createdAt": "2026-06-03T00:00:00Z",
  "uploadedAt": "2026-06-03T00:01:00Z",
  "expiredAt": null,
  "deletedAt": null
}
```

### 내부 흐름

```text
FileController.completeUpload
-> FileService.completeUpload
-> FileRecordRepository.findById
-> 상태 확인
-> S3Client.headObject
-> validateUploadedObject
-> FileRecord.complete
```

### 실패 응답 예시

S3에 아직 객체가 없으면 `409 Conflict`가 반환됩니다. 파일 상태는 `PENDING`으로 유지되므로, 실제 업로드가 끝난 뒤 complete API를 다시 호출할 수 있습니다.

```json
{
  "node": "S3_OBJECT_NOT_FOUND",
  "message": "아직 S3에 파일이 업로드되지 않았습니다. key=uploads/uuid-sample.txt",
  "errors": []
}
```

업로드된 객체가 파일 크기 또는 Content-Type 정책을 통과하지 못하면 `400 Bad Request`가 반환되고 파일 상태는 `REJECTED`로 변경됩니다.

```json
{
  "node": "CONTENT_TYPE_MISMATCH",
  "message": "요청 Content-Type과 실제 Content-Type이 다릅니다. requested=text/plain, actual=image/png",
  "errors": []
}
```

이미 삭제된 파일을 완료 처리하려고 하면 `409 Conflict`가 반환됩니다.

```json
{
  "node": "DELETED_FILE_CANNOT_COMPLETE",
  "message": "삭제된 파일은 완료 처리할 수 없습니다. fileId=file-record-id",
  "errors": []
}
```

이미 거부된 파일을 완료 처리하려고 하면 `409 Conflict`가 반환됩니다.

```json
{
  "node": "FAILED_FILE_CANNOT_COMPLETE",
  "message": "실패 처리된 파일은 완료 처리할 수 없습니다. fileId=file-record-id",
  "errors": []
}
```

## 8. 파일 기록 목록 조회

```http
GET /files/records
```

H2 데이터베이스에 저장된 파일 기록 목록을 조회합니다.

S3 객체 목록을 조회하는 `GET /files`와 다르게, 이 API는 Spring Data JPA 기반 `FileRecordRepository`에 저장된 업로드 기록을 조회합니다.

### curl

```bash
curl http://localhost:8080/files/records
```

### Response

```json
[
  {
    "id": "file-record-id",
    "bucket": "s3-lab-bucket",
    "key": "uploads/uuid-sample.txt",
    "originalFilename": "sample.txt",
    "requestedContentType": "text/plain",
    "status": "UPLOADED",
    "size": 1234,
    "actualContentType": "text/plain",
    "rejectedReason": null,
    "createdAt": "2026-06-03T00:00:00Z",
    "uploadedAt": "2026-06-03T00:01:00Z",
    "expiredAt": null,
    "deletedAt": null
  }
]
```

### 내부 흐름

```text
FileController.listRecords
-> FileService.listRecords
-> FileRecordRepository.findAll
```

## 9. fileId 기반 Presigned GET URL 발급

```http
GET /files/{fileId}/presigned-get-url
```

S3 key를 직접 전달하지 않고, 파일 기록 ID로 다운로드용 Presigned URL을 발급합니다.

파일 상태가 `UPLOADED`가 아닌 경우 다운로드 URL을 발급하지 않습니다.

### Request

| 이름 | 위치 | 타입 | 설명 |
| --- | --- | --- | --- |
| `fileId` | path variable | string | 파일 기록 ID |

### curl

```bash
curl http://localhost:8080/files/{fileId}/presigned-get-url
```

### Response

```json
{
  "key": "uploads/uuid-sample.txt",
  "url": "http://localhost:9000/s3-lab-bucket/uploads/uuid-sample.txt?...",
  "method": "GET",
  "expressInSeconds": 600
}
```

### 내부 흐름

```text
FileController.createPresignedGetUrlByFileId
-> FileService.createPresignedGetUrlByFileId
-> FileRecordRepository.findById
-> status 확인
-> FileService.createPresignedGetUrl
```

### 실패 응답 예시

업로드 완료 상태가 아닌 파일에 대해 다운로드 URL을 요청하면 `409 Conflict`가 반환됩니다.

```json
{
  "node": "FILE_NOT_UPLOADED",
  "message": "업로드 완료된 파일만 다운로드 URL을 발급할 수 있습니다. status=PENDING",
  "errors": []
}
```

## 10. fileId 기반 파일 삭제

```http
DELETE /files/{fileId}
```

파일 기록 ID로 S3 객체를 삭제하고 파일 상태를 `DELETED`로 변경합니다.

이미 `DELETED` 상태인 파일에 다시 호출하면 추가 작업 없이 `204 No Content`를 반환합니다.

### Request

| 이름 | 위치 | 타입 | 설명 |
| --- | --- | --- | --- |
| `fileId` | path variable | string | 파일 기록 ID |

### curl

```bash
curl -X DELETE http://localhost:8080/files/{fileId}
```

### Response

```http
204 No Content
```

### 내부 흐름

```text
FileController.deleteByFileId
-> FileService.deleteByFileId
-> FileRecordRepository.findById
-> S3Client.deleteObject
-> FileRecord.delete
```

## 11. 만료된 PENDING 파일 정리

```http
POST /files/expire-pending
```

Presigned PUT URL을 발급받았지만 제한 시간 안에 complete 처리되지 않은 파일 기록을 만료 처리합니다.

만료 기준은 `file.pending-expiration-seconds` 설정값이며 현재 10초입니다.

이 API는 수동 정리용입니다. 같은 정리 로직은 `FileCleanupScheduler`에서도 `file.expire-schedule-ms` 주기마다 자동 실행됩니다.

이 API는 아래 두 경우를 모두 정리합니다.

- 클라이언트가 S3에 업로드하지 않아 객체가 없는 `PENDING` 기록
- 클라이언트가 S3 업로드는 완료했지만 complete API를 호출하지 않은 `PENDING` 기록

만료 대상의 S3 객체가 존재하면 삭제하고, 파일 기록 상태를 `EXPIRED`로 변경합니다.

### curl

```bash
curl -X POST http://localhost:8080/files/expire-pending
```

### Response

```json
[
  {
    "fileId": "file-record-id",
    "key": "uploads/uuid-sample.txt",
    "objectDeleted": true
  }
]
```

`objectDeleted`는 만료 처리 중 S3 객체를 실제로 삭제했는지 나타냅니다.

- `true`: S3 객체가 존재해서 삭제함
- `false`: S3 객체가 없어 파일 기록만 만료 처리함

### 내부 흐름

```text
FileController.expirePendingFiles
-> FileService.expirePendingFiles
-> FileRecordRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc
-> PageRequest로 최대 100개 조회
-> S3Client.headObject
-> S3Client.deleteObject
-> FileRecord.expire
```
