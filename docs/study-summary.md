# S3 Lab 학습 총정리

이 문서는 프로젝트 전체 흐름을 공부용으로 한 번에 정리한 문서입니다.

## 프로젝트 목표

이 프로젝트는 Spring Boot 서버에서 S3 호환 스토리지인 MinIO를 사용해 파일 업로드, 다운로드, Presigned URL, 파일 상태 추적, 만료 정리까지 학습하는 예제입니다.

핵심은 두 가지입니다.

- 파일 바이트는 S3 또는 MinIO에 저장합니다.
- 파일의 업무 상태는 JPA Entity인 `FileRecord`로 데이터베이스에 저장합니다.

## 전체 구조

```text
Client
-> FileController
-> FileService
-> FileRecordRepository -> H2 Database
-> S3Client / S3Presigner -> MinIO
```

각 계층의 역할:

- `FileController`: HTTP 요청과 응답 처리
- `FileService`: 파일 업로드, 검증, 상태 변경, S3 호출의 핵심 로직
- `FileRecordRepository`: JPA로 파일 기록 저장과 조회
- `FileRecord`: 파일 상태를 가진 JPA Entity
- `FileCleanupScheduler`: 완료되지 않은 파일 자동 만료 처리
- `S3Config`: `S3Client`, `S3Presigner` Bean 생성

## 일반 Multipart 업로드

```text
Client
-> POST /files
-> Spring Boot 서버가 MultipartFile 수신
-> S3Client.putObject
-> MinIO에 객체 저장
```

이 방식은 서버가 파일 바이트를 직접 받습니다. 구현은 단순하지만 파일 크기가 커질수록 서버 부하가 커집니다.

## Presigned PUT 업로드

Presigned PUT은 클라이언트가 서버를 거치지 않고 S3에 직접 파일을 올리는 방식입니다.

```text
1. Client -> POST /files/presigned-put-url
2. Server -> FileRecord 생성, PENDING 저장
3. Server -> Presigned PUT URL 반환
4. Client -> Presigned URL로 MinIO에 직접 PUT
5. Client -> POST /files/{fileId}/complete
6. Server -> HeadObject로 S3 객체 확인
7. Server -> 파일 정책 검증 후 UPLOADED 변경
```

서버는 파일 바이트를 받지 않기 때문에 완료 여부를 직접 알 수 없습니다. 그래서 complete API에서 `HeadObject`로 객체 존재 여부와 메타데이터를 확인합니다.

## 파일 상태

`FileStatus`는 파일 기록의 생명주기를 나타냅니다.

| 상태 | 의미 |
| --- | --- |
| `PENDING` | Presigned PUT URL은 발급됐지만 complete 검증 전 |
| `UPLOADED` | S3 객체 존재와 파일 정책 검증이 끝난 상태 |
| `REJECTED` | S3 객체는 있지만 크기 또는 Content-Type 정책 위반 |
| `EXPIRED` | 제한 시간 안에 complete 되지 않아 만료된 상태 |
| `DELETED` | fileId 기반 삭제가 처리된 상태 |

상태 전환:

```text
PENDING -> UPLOADED
PENDING -> REJECTED
PENDING -> EXPIRED
PENDING/UPLOADED/REJECTED/EXPIRED -> DELETED
```

## JPA 전환

이전에는 파일 기록을 메모리 컬렉션에 저장할 수 있었지만, 지금은 `FileRecord`를 JPA Entity로 관리합니다.

`FileRecord`의 핵심 JPA 설정:

- `@Entity`: JPA 관리 대상
- `@Table(name = "file_records")`: 테이블 이름 지정
- `@Id`: `id`를 기본키로 사용
- `@Enumerated(EnumType.STRING)`: enum을 문자열로 저장
- `protected FileRecord(){}`: JPA 기본 생성자

Repository는 `JpaRepository<FileRecord, String>`을 상속합니다. 그래서 `save`, `findById`, `findAll` 같은 기본 메서드는 직접 구현하지 않아도 됩니다.

만료 대상 조회는 파생 쿼리 메서드를 사용합니다.

```java
findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
    FileStatus.PENDING,
    cutoff,
    pageable
)
```

이 메서드는 `PENDING` 상태이면서 `createdAt`이 기준 시각보다 오래된 기록을 오래된 순서로 가져옵니다. `PageRequest.of(0, 100)`으로 한 번에 최대 100개만 처리합니다.

## H2 설정

현재 데이터베이스는 H2 인메모리 DB입니다.

```properties
spring.datasource.url=jdbc:h2:mem:s3lab
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true
```

주의할 점:

- 애플리케이션이 실행되는 동안만 H2 데이터가 유지됩니다.
- 재시작하면 `file_records` 테이블과 데이터가 다시 생성됩니다.
- MinIO에 저장된 실제 S3 객체는 애플리케이션 재시작만으로 삭제되지 않습니다.

## 만료 정리

Presigned PUT URL을 발급받고 파일을 업로드했더라도 complete API를 호출하지 않으면 서버 입장에서는 파일이 아직 `PENDING`입니다.

현재 설정:

```properties
file.pending-expiration-seconds=10
file.expire-schedule-ms=10000
```

만료 기준:

- `PENDING` 상태
- `createdAt + 10초`가 현재 시각보다 이전

만료 처리 흐름:

```text
FileService.expirePendingFiles
-> cutoff 계산
-> JPA Repository로 만료 대상 최대 100개 조회
-> S3 객체 존재 여부 HeadObject로 확인
-> 객체가 있으면 deleteObject
-> FileRecord.expire
-> EXPIRED 저장
```

`POST /files/expire-pending`으로 수동 실행할 수 있고, `FileCleanupScheduler`가 10초마다 자동 실행합니다.

## 스케줄러

`S3labApplication`의 `@EnableScheduling`이 스케줄러 기능을 활성화합니다.

`FileCleanupScheduler`는 아래 설정값을 사용합니다.

```java
@Scheduled(fixedDelayString = "${file.expire-schedule-ms}")
```

`fixedDelay`는 이전 실행이 끝난 뒤 지정된 시간이 지나면 다시 실행한다는 뜻입니다. 현재는 10000ms이므로 이전 정리 작업이 끝난 뒤 10초 후 다시 실행됩니다.

## 로깅

이 프로젝트는 SLF4J logger를 사용합니다.

```java
private static final Logger log = LoggerFactory.getLogger(FileService.class);
```

주요 로그:

- Presigned PUT URL 발급
- 업로드 완료 검증 성공
- S3 객체 미존재
- 파일 정책 위반으로 `REJECTED` 처리
- fileId 기반 삭제
- PENDING 파일 만료
- S3 객체 삭제 실패
- 스케줄러 만료 정리 결과

로그 레벨 설정:

```properties
logging.level.com.example.s3lab=DEBUG
logging.level.software.amazon.awssdk=WARN
```

애플리케이션 로그는 자세히 보고, AWS SDK 내부 로그는 너무 많아지지 않도록 WARN 이상만 출력합니다.

## API별 학습 포인트

| API | 학습 포인트 |
| --- | --- |
| `POST /files` | 서버 경유 Multipart 업로드 |
| `GET /files` | S3 객체 목록 조회 |
| `GET /files/download?key=...` | 서버 경유 다운로드 |
| `POST /files/presigned-put-url` | Presigned PUT URL 발급과 JPA 기록 생성 |
| `POST /files/{fileId}/complete` | HeadObject 검증과 상태 확정 |
| `GET /files/records` | JPA에 저장된 파일 기록 조회 |
| `GET /files/{fileId}/presigned-get-url` | 상태 기반 다운로드 URL 발급 제한 |
| `DELETE /files/{fileId}` | S3 객체 삭제와 상태 변경 |
| `POST /files/expire-pending` | 미완료 파일 수동 만료 정리 |

## 중요한 구분

S3 객체와 DB 기록은 서로 다릅니다.

- S3 객체: 실제 파일 바이트
- DB 기록: 파일 상태, content type, size, createdAt 같은 메타데이터

Presigned PUT 업로드에서 클라이언트가 S3에 파일을 올렸더라도 complete API를 호출하지 않으면 DB 기록은 `PENDING`입니다. 이 차이를 이해하는 것이 이 프로젝트의 가장 중요한 포인트입니다.
