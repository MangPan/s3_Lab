# S3와 MinIO 설정

이 프로젝트는 실제 AWS S3 대신 로컬 MinIO를 사용합니다.

MinIO는 S3 API와 호환되는 오브젝트 스토리지입니다. 로컬에서 실행할 수 있어서 S3 업로드, 다운로드, Presigned URL 흐름을 학습하기에 적합합니다.

## Docker Compose

`docker-compose.yml`

```yaml
services:
  minio:
    image: minio/minio:latest
    container_name: s3-lab-minio
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin123
    command: server /data --console-address ":9001"
    volumes:
      - minio-data:/data
volumes:
  minio-data:
```

포트:

- `9000`: S3 API endpoint
- `9001`: MinIO 웹 콘솔

실행:

```bash
docker compose up -d
```

MinIO 콘솔:

```text
http://localhost:9001
```

## 애플리케이션 설정

`application.properties`

```properties
s3.endpoint=http://localhost:9000
s3.region=ap-northeast-2
s3.bucket=s3-lab-bucket
s3.access-key=minioadmin
s3.secret-key=minioadmin123
```

## 버킷

현재 애플리케이션은 아래 버킷을 사용하도록 설정되어 있습니다.

```text
s3-lab-bucket
```

MinIO 콘솔에서 같은 이름의 버킷을 직접 생성해야 합니다.

버킷이 없으면 업로드, 목록 조회, 다운로드 같은 S3 API 호출이 실패합니다.

## `endpointOverride`

`S3Config`에서는 `endpointOverride`를 사용합니다.

```java
.endpointOverride(URI.create(endpoint))
```

AWS SDK는 기본적으로 실제 AWS S3 endpoint를 대상으로 요청을 보냅니다.

이 프로젝트는 로컬 MinIO를 사용하기 때문에 endpoint를 다음 값으로 바꿉니다.

```text
http://localhost:9000
```

## Region

```properties
s3.region=ap-northeast-2
```

MinIO는 로컬에서 실행되지만 S3 API 호환을 위해 region 값을 사용합니다.

실제 AWS S3를 사용할 때는 버킷이 생성된 region과 맞춰야 합니다.

## Credentials

현재 설정:

```properties
s3.access-key=minioadmin
s3.secret-key=minioadmin123
```

MinIO root 계정과 같은 값입니다.

학습용 프로젝트에서는 설정 파일에 직접 적어도 흐름을 이해하기 쉽지만, 실제 서비스에서는 피해야 합니다.

실서비스에서 고려할 방식:

- 환경 변수
- JVM system property
- AWS profile
- IAM Role
- Kubernetes service account와 연동된 IAM Role

## Path-style access

`S3Config`의 `S3Client` 설정:

```java
.forcePathStyle(true)
```

`S3Presigner` 설정:

```java
.serviceConfiguration(
    S3Configuration.builder()
        .pathStyleAccessEnabled(true)
        .build()
)
```

S3 주소 형식은 크게 두 가지가 있습니다.

### Virtual-hosted-style

```text
http://bucket-name.localhost:9000/object-key
```

### Path-style

```text
http://localhost:9000/bucket-name/object-key
```

MinIO 로컬 환경에서는 path-style을 사용하는 편이 단순합니다.

그래서 이 프로젝트는 `forcePathStyle(true)`와 `pathStyleAccessEnabled(true)`를 설정합니다.

## 실제 AWS S3로 바꿀 때

실제 AWS S3를 사용할 때는 다음 내용을 확인해야 합니다.

- `s3.endpoint` 제거 또는 AWS S3 endpoint로 변경
- `s3.region`을 실제 버킷 region으로 변경
- access key, secret key를 안전한 방식으로 주입
- `forcePathStyle(true)` 제거 검토
- 버킷 정책과 CORS 설정 확인
- Presigned PUT을 브라우저에서 직접 호출한다면 CORS 설정 필요

## CORS

브라우저에서 Presigned PUT URL을 사용해 직접 S3 또는 MinIO에 업로드하려면 CORS 설정이 필요할 수 있습니다.

서버에서 curl로 테스트할 때는 CORS 문제가 발생하지 않습니다.

하지만 브라우저에서는 다음 요청이 차단될 수 있습니다.

```text
브라우저 -> MinIO 또는 S3
```

이 경우 버킷에 허용 origin, method, header를 설정해야 합니다.

