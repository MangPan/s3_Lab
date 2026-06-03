# API 정리

이 문서는 `FileController`에 정의된 API를 기준으로 작성합니다.

기본 경로:

```text
/files
```

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
-> FileRecord 생성, 상태 PENDING
-> FileRecordRepository.save
-> S3Presigner.presignPutObject
```

## 7. Presigned PUT 업로드 완료 검증

```http
POST /files/{fileId}/complete
```

Presigned PUT URL로 S3에 직접 업로드한 뒤, 백엔드에 업로드 완료를 알리는 API입니다.

백엔드는 S3에 `HeadObject` 요청을 보내 실제 객체가 존재하는지 확인합니다. 확인에 성공하면 파일 기록 상태를 `UPLOADED`로 변경합니다.

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
  "createdAt": "2026-06-03T00:00:00Z",
  "uploadedAt": "2026-06-03T00:01:00Z"
}
```

### 내부 흐름

```text
FileController.completeUpload
-> FileService.completeUpload
-> FileRecordRepository.findById
-> S3Client.headObject
-> FileRecord.complete
```

## 8. 파일 기록 목록 조회

```http
GET /files/records
```

애플리케이션 메모리에 저장된 파일 기록 목록을 조회합니다.

S3 객체 목록을 조회하는 `GET /files`와 다르게, 이 API는 `FileRecordRepository`에 저장된 업로드 기록을 조회합니다.

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
    "createdAt": "2026-06-03T00:00:00Z",
    "uploadedAt": "2026-06-03T00:01:00Z"
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

