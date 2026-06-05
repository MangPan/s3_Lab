# Presigned URL 흐름

Presigned URL은 서버가 가진 S3 접근 권한을 이용해 특정 작업을 일정 시간 동안 허용하는 URL입니다.

이 프로젝트에서는 두 종류의 Presigned URL을 사용합니다.

- 다운로드용 Presigned GET URL
- 업로드용 Presigned PUT URL

## 일반 업로드와 Presigned 업로드 비교

### 일반 Multipart 업로드

```text
클라이언트
-> Spring Boot 서버
-> S3 또는 MinIO
```

파일 바이트가 백엔드 서버를 거쳐 S3로 전달됩니다.

장점:

- 구현 흐름이 단순합니다.
- 서버에서 파일 검증, 로깅, 권한 확인을 직접 처리하기 쉽습니다.

단점:

- 큰 파일을 업로드하면 백엔드 서버에 부하가 생깁니다.
- 파일 전송 트래픽이 서버를 한 번 거칩니다.

### Presigned PUT 업로드

```text
1. 클라이언트 -> Spring Boot 서버: 업로드 URL 요청
2. Spring Boot 서버 -> 클라이언트: Presigned PUT URL 응답
3. 클라이언트 -> S3 또는 MinIO: 파일 직접 PUT
4. 클라이언트 -> Spring Boot 서버: 업로드 완료 검증 요청
5. Spring Boot 서버 -> S3 또는 MinIO: HeadObject로 실제 업로드 확인
6. Spring Boot 서버: 파일 크기와 Content-Type 검증
7. Spring Boot 서버: 완료되지 않은 PENDING 기록 만료 정리
```

파일 바이트가 백엔드 서버를 거치지 않고 클라이언트에서 S3로 직접 전송됩니다.

장점:

- 백엔드 서버의 파일 전송 부하가 줄어듭니다.
- 대용량 파일 업로드에 더 적합합니다.

단점:

- 업로드 URL 발급, 직접 업로드, 완료 검증으로 흐름이 나뉩니다.
- 서버는 클라이언트가 실제로 업로드를 완료했는지 따로 확인해야 합니다.

## Presigned GET URL 흐름

### 1. 클라이언트가 다운로드 URL 요청

```http
GET /files/presigned-get-url?key=uploads/uuid-sample.txt
```

또는 fileId를 사용할 수 있습니다.

```http
GET /files/{fileId}/presigned-get-url
```

### 2. 서버가 Presigned GET URL 생성

`FileService.createPresignedGetUrl`에서 다음 작업을 수행합니다.

```text
GetObjectRequest 생성
-> GetObjectPresignRequest 생성
-> S3Presigner.presignGetObject 호출
-> URL 문자열 반환
```

### 3. 클라이언트가 URL로 직접 다운로드

응답의 `url`에 GET 요청을 보내면 S3 또는 MinIO에서 직접 파일을 다운로드합니다.

이때 Spring Boot 서버는 파일 바이너리 전송에 참여하지 않습니다.

## Presigned PUT URL 흐름

## 1. 업로드 URL 발급 요청

```http
POST /files/presigned-put-url
Content-Type: application/json
```

```json
{
  "filename": "sample.txt",
  "contentType": "text/plain"
}
```

서버는 요청한 Content-Type이 허용 목록에 있는지 먼저 검사합니다.

허용되는 Content-Type:

- `image/jpeg`
- `image/png`
- `image/webp`
- `text/plain`

그 다음 파일명을 바탕으로 고유 S3 key를 생성합니다.

예시:

```text
uploads/uuid-sample.txt
```

그리고 `FileRecord`를 생성해 `PENDING` 상태로 저장합니다.

```text
FileRecord.status = PENDING
```

## 2. Presigned PUT URL 응답

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

중요한 값:

- `fileId`: 나중에 완료 검증을 호출할 때 사용
- `key`: S3에 저장될 object key
- `url`: 클라이언트가 직접 PUT 요청을 보낼 URL
- `contentType`: PUT 요청 시 맞춰 보내야 하는 Content-Type

## 3. 클라이언트가 S3에 직접 PUT

응답받은 `url`로 파일을 업로드합니다.

```bash
curl -X PUT "{presigned-url}" \
  -H "Content-Type: text/plain" \
  --data-binary "@./sample.txt"
```

주의할 점:

- Presigned URL 생성 시 사용한 Content-Type과 실제 PUT 요청의 Content-Type이 같아야 합니다.
- URL은 10분 동안만 유효합니다.
- URL 만료 후에는 다시 발급받아야 합니다.
- 업로드 완료 검증 시 파일 크기는 5MB 이하여야 합니다.
- URL 만료 전에 S3 업로드가 끝났더라도 complete API를 호출하지 않으면 서버의 파일 기록은 계속 `PENDING`입니다.

## 4. 업로드 완료 검증 요청

파일 PUT 요청이 성공하면 백엔드에 완료 검증을 요청합니다.

```http
POST /files/{fileId}/complete
```

서버는 `fileId`로 `FileRecord`를 찾습니다.

그 다음 S3에 `HeadObject` 요청을 보냅니다.

```text
HeadObjectRequest
-> S3Client.headObject
```

`HeadObject`는 파일 전체를 다운로드하지 않고, 객체의 존재 여부와 메타데이터만 확인합니다.

확인하는 값:

- 객체 존재 여부
- 파일 크기
- 실제 Content-Type
- 요청 당시 Content-Type과 실제 Content-Type 일치 여부

## 5. 상태 변경

S3 객체가 실제로 존재하고 검증 조건을 통과하면 `FileRecord.complete`가 호출됩니다.

```text
PENDING -> UPLOADED
```

함께 저장되는 값:

- 실제 파일 크기
- 실제 Content-Type
- 업로드 완료 시각

파일 크기나 Content-Type 정책을 위반하면 `FileRecord.reject`가 호출되어 상태가 `REJECTED`로 바뀝니다.

S3 객체가 아직 없으면 `S3_OBJECT_NOT_FOUND`가 반환되고 상태는 `PENDING`으로 유지됩니다. 클라이언트가 업로드를 끝낸 뒤 complete API를 다시 호출할 수 있습니다.

## 상태 전환

```text
Presigned PUT URL 발급
-> FileRecord 생성
-> PENDING
-> 클라이언트가 S3에 직접 업로드
-> complete API 호출
-> HeadObject 성공
-> 파일 검증 성공
-> UPLOADED
```

실패 흐름:

```text
PENDING
-> complete API 호출
-> 파일 크기 또는 Content-Type 검증 실패
-> REJECTED
```

S3 객체 미존재 흐름:

```text
PENDING
-> complete API 호출
-> S3 객체 미존재
-> PENDING 유지
```

만료 흐름:

```text
PENDING
-> 생성 후 10초 초과
-> expire-pending API 호출
-> S3 객체가 존재하면 삭제
-> EXPIRED
```

삭제 흐름:

```text
PENDING 또는 UPLOADED 또는 REJECTED 또는 EXPIRED
-> deleteByFileId API 호출
-> S3 deleteObject
-> DELETED
```

## 멱등성 처리

`completeUpload`은 이미 `UPLOADED` 상태인 파일에 대해 다시 호출되어도 같은 결과를 반환합니다.

```java
if(fileRecord.getStatus() == FileStatus.UPLOADED){
    return FileRecordResponse.from(fileRecord);
}
```

즉, 완료 API가 중복 호출되어도 이미 완료된 파일은 다시 검증하지 않고 현재 기록을 반환합니다.

`deleteByFileId`도 이미 `DELETED` 상태인 파일에 대해 다시 호출되면 추가 삭제 요청 없이 종료합니다.

## PENDING 파일 만료 정리

Presigned PUT URL 발급 후 complete API가 호출되지 않으면 파일 기록은 `PENDING` 상태로 남습니다. 이 프로젝트는 `POST /files/expire-pending` API로 생성 후 10초가 지난 `PENDING` 기록을 만료 처리합니다.

```http
POST /files/expire-pending
```

정리 기준:

- `status`가 `PENDING`인 기록만 대상입니다.
- `createdAt + 10초`가 현재 시각보다 이전이면 만료 대상입니다.
- 만료 대상의 S3 객체가 이미 존재하면 삭제합니다.
- 파일 기록은 `EXPIRED` 상태가 되고 `expiredAt`이 저장됩니다.

응답의 `objectDeleted` 값은 S3 객체를 실제로 삭제했는지 나타냅니다. 클라이언트가 파일을 PUT하지 않은 상태로 만료되면 `false`, 파일은 올라갔지만 complete 하지 않은 상태로 만료되면 `true`가 됩니다.
