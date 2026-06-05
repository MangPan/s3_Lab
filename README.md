# s3lab

Spring Boot와 MinIO를 이용해 S3 파일 업로드, 다운로드, Presigned URL 흐름을 학습하는 프로젝트입니다.

이 프로젝트는 실제 AWS S3 대신 로컬에서 실행할 수 있는 S3 호환 스토리지인 MinIO를 사용합니다. Spring Boot 애플리케이션에서 AWS SDK for Java v2를 통해 S3 API를 호출하는 구조를 연습하는 데 초점을 둡니다.

## 학습 목표

- Multipart 방식으로 백엔드 서버를 거쳐 파일 업로드하기
- S3 버킷의 객체 목록 조회하기
- S3 객체를 백엔드 서버를 통해 다운로드하기
- Presigned GET URL을 발급해 클라이언트가 직접 다운로드하기
- Presigned PUT URL을 발급해 클라이언트가 S3에 직접 업로드하기
- 업로드 완료 후 `HeadObject`로 실제 S3 객체 존재 여부 검증하기
- 파일 업로드 상태를 `PENDING`, `UPLOADED`, `REJECTED`, `EXPIRED`, `DELETED`로 관리하기
- 완료되지 않은 Presigned PUT 업로드 기록을 만료 처리하고, 이미 올라온 미완료 객체를 정리하기
- 전역 예외 처리로 일관된 에러 응답 내려주기

## 기술 스택

- Java 21
- Spring Boot 3.5.14
- Gradle
- AWS SDK for Java v2
- MinIO
- Docker Compose
- Lombok

## 프로젝트 구조

```text
src/main/java/com/example/s3lab
├── config
│   └── S3Config.java
├── domain
│   ├── FileRecord.java
│   └── FileStatus.java
├── global
│   └── exception
└── file
    ├── controller
    │   └── FileController.java
    ├── dto
    ├── repository
    │   └── FileRecordRepository.java
    └── service
        └── FileService.java
```

## 실행 방법

### 1. MinIO 실행

```bash
docker compose up -d
```

MinIO 콘솔:

```text
http://localhost:9001
```

기본 계정:

```text
ID: minioadmin
PW: minioadmin123
```

### 2. 버킷 생성

MinIO 콘솔에서 아래 버킷을 생성합니다.

```text
s3-lab-bucket
```

애플리케이션 설정의 `s3.bucket` 값과 버킷 이름이 같아야 합니다.

### 3. 애플리케이션 실행

```bash
./gradlew bootRun
```

## 주요 설정

`src/main/resources/application.properties`

```properties
s3.endpoint=http://localhost:9000
s3.region=ap-northeast-2
s3.bucket=s3-lab-bucket
s3.access-key=minioadmin
s3.secret-key=minioadmin123
```

학습 편의를 위해 access key와 secret key가 설정 파일에 직접 들어 있습니다. 실제 서비스에서는 환경 변수, AWS profile, IAM Role 같은 안전한 인증 방식을 사용해야 합니다.

## 주요 API

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/files` | Multipart 파일 업로드 |
| `GET` | `/files` | S3 객체 목록 조회 |
| `GET` | `/files/download?key=...` | 파일 다운로드 |
| `DELETE` | `/files?key=...` | 파일 삭제 |
| `DELETE` | `/files/{fileId}` | fileId 기반 파일 삭제 |
| `GET` | `/files/presigned-get-url?key=...` | key 기반 Presigned GET URL 발급 |
| `POST` | `/files/presigned-put-url` | Presigned PUT URL 발급 |
| `POST` | `/files/{fileId}/complete` | Presigned PUT 업로드 완료 검증 |
| `GET` | `/files/records` | 파일 기록 목록 조회 |
| `GET` | `/files/{fileId}/presigned-get-url` | fileId 기반 Presigned GET URL 발급 |
| `POST` | `/files/expire-pending` | 만료된 PENDING 파일 정리 |

자세한 API 설명은 [docs/api.md](docs/api.md)를 참고합니다.

## 문서

- [API 정리](docs/api.md)
- [코드 구조 설명](docs/code-map.md)
- [예외 처리](docs/error-handling.md)
- [Presigned URL 흐름](docs/presigned-url-flow.md)
- [S3와 MinIO 설정](docs/s3-and-minio.md)
