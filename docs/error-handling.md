# 예외 처리

이 프로젝트는 `global/exception` 패키지에서 전역 예외 처리를 관리합니다.

Controller나 Service에서 예외가 발생하면 `GlobalExceptionHandler`가 HTTP 상태 코드와 공통 에러 응답으로 변환합니다.

## 공통 에러 응답

```json
{
  "node": "ERROR_CODE",
  "message": "에러 메시지",
  "errors": []
}
```

필드:

- `node`: 에러 코드
- `message`: 사용자 또는 개발자가 확인할 메시지
- `errors`: 필드 검증 실패 목록

## 필드 검증 실패 응답

`@Valid` 검증에 실패하면 `MethodArgumentNotValidException`이 발생합니다.

예시:

```json
{
  "node": "VALIDATION_FAILED",
  "message": "요청 값 검증에 실패했습니다",
  "errors": [
    {
      "field": "contentType",
      "message": "Content-Type is required."
    }
  ]
}
```

## 예외 클래스

| 예외 | HTTP Status | 용도 |
| --- | --- | --- |
| `BadRequestException` | `400 Bad Request` | 요청 값이 잘못된 경우 |
| `NotFoundException` | `404 Not Found` | 요청한 리소스를 찾지 못한 경우 |
| `ConflictException` | `409 Conflict` | 현재 리소스 상태와 요청이 충돌하는 경우 |
| `MethodArgumentNotValidException` | `400 Bad Request` | `@Valid` 필드 검증 실패 |
| `Exception` | `500 Internal Server Error` | 처리하지 못한 서버 내부 오류 |

## 현재 사용되는 에러 코드

| 코드 | Status | 발생 상황 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | request body 필드 검증 실패 |
| `INVALID_CONTENT_TYPE` | 400 | Presigned PUT URL 요청 Content-Type이 허용되지 않음 |
| `FILE_SIZE_EXCEEDED` | 400 | 업로드된 파일 크기가 5MB를 초과함 |
| `INVALID_UPLOADED_CONTENT_TYPE` | 400 | 업로드된 객체의 Content-Type이 허용되지 않음 |
| `CONTENT_TYPE_MISMATCH` | 400 | 요청 Content-Type과 실제 업로드 Content-Type이 다름 |
| `FILE_RECORD_NOT_FOUND` | 404 | fileId에 해당하는 파일 기록이 없음 |
| `S3_OBJECT_NOT_FOUND` | 409 | complete 호출 시 S3 객체가 아직 없음 |
| `DELETED_FILE_CANNOT_COMPLETE` | 409 | 삭제된 파일을 완료 처리하려고 함 |
| `FAILED_FILE_CANNOT_COMPLETE` | 409 | 거부 처리된 파일을 완료 처리하려고 함 |
| `FILE_NOT_UPLOADED` | 409 | 업로드 완료 전 다운로드 URL을 요청함 |
| `INTERNAL_SERVER_ERROR` | 500 | 처리하지 못한 서버 내부 오류 |

## 파일 상태와 예외

파일 기록은 다음 상태를 가질 수 있습니다.

- `PENDING`: Presigned PUT URL은 발급됐지만 완료 검증 전
- `UPLOADED`: S3 객체 존재와 검증 조건을 통과한 상태
- `REJECTED`: S3 객체는 업로드됐지만 파일 정책 검증에 실패한 상태
- `EXPIRED`: 제한 시간 안에 complete 처리되지 않아 만료된 상태
- `DELETED`: 삭제 처리된 상태

상태별 주요 제약:

- `UPLOADED` 상태는 complete API를 다시 호출해도 그대로 응답합니다.
- `DELETED` 상태는 complete API를 호출할 수 없습니다.
- `REJECTED` 상태는 complete API를 호출할 수 없습니다.
- `UPLOADED`가 아닌 상태에서는 fileId 기반 다운로드 Presigned URL을 발급할 수 없습니다.
- `PENDING` 상태가 10초를 넘으면 `POST /files/expire-pending` 호출 시 `EXPIRED`로 변경됩니다.
- 만료 대상 파일의 S3 객체가 이미 존재하면 만료 처리 중 함께 삭제됩니다.

## Presigned PUT 검증

Presigned PUT URL 발급 시 요청 Content-Type을 검증합니다.

허용 목록:

- `image/jpeg`
- `image/png`
- `image/webp`
- `text/plain`

업로드 완료 검증 시 `HeadObject` 결과를 기준으로 다음 조건을 확인합니다.

- S3 객체가 존재해야 합니다.
- 파일 크기는 5MB 이하여야 합니다.
- 실제 Content-Type이 허용 목록에 있어야 합니다.
- 요청 당시 Content-Type과 실제 Content-Type이 같아야 합니다.

검증 조건 중 파일 크기나 Content-Type 정책을 위반하면 파일 기록은 `REJECTED`로 바뀌고 거부 사유가 `rejectedReason`에 저장됩니다.

S3 객체가 아직 존재하지 않는 경우에는 `409 Conflict`가 반환되지만 파일 상태는 `PENDING`으로 유지됩니다. 이후 클라이언트가 업로드를 완료하면 complete API를 다시 호출할 수 있습니다.
