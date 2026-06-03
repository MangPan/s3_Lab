package com.example.s3lab.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3Config {

    /**
     * - StandardRetryStrategy 표준 재시도 전략
     * maxAttempts(10) == 최대 10번 재시도
     * 
     * - AdaptiveRetryStrategy
     *  클라이언트가 요청을 보내다가 서버로부터 Throttling, 429 에러 등을 받으면
     *  자신이 요청을 보내는 속도 (Rate Limiting) 자체를 실시간으로 늦춰줌
     */
    StandardRetryStrategy retryStrategy = AwsRetryStrategy.standardRetryStrategy()
        .toBuilder()
        .maxAttempts(10) // 10번 재시도
        .build();

    @Bean
    public S3Client s3Client(
        // application-properties
        @Value("${s3.endpoint}") String endpoint,
        @Value("${s3.region}") String region,
        @Value("${s3.access-key}") String accessKey,
        @Value("${s3.secret-key}") String secretKey
    ){
        return S3Client.builder()
            // AWS S3 가 아닌 다른 URI를 사용. Override
            .endpointOverride(URI.create(endpoint))

            /*
            AWS 같은 클라우드 서비스는 같은 s3여도 region을 통해 구분을 함
            이를 통해 여러 국가에 서비스를 하기 위함임
            이 실습에서는 MINIO를 이용해 로컬에서 s3호환 버킷서버를 띄웠음
            로컬에서 동작하지만 MINIO도 s3와 호환을 위해 region을 입력하긴 했음
            */
            .region(Region.of(region))

            /* 
            S3 계열 인증은 보안을 위해 AccessKey ID와 Secret Access Key라는 쌍을 이용해 요청 메시지 자체를 암호화 서명(Signature V4) 하는 방식을 사용한다.
            여러 인증 방식을 제공함
            - StaticCredentialsProvider 
                액세스키와 시크릿 키를 하드코딩으로 넘김, 현재 사용하는 방식

            - EnvironmentVariableCredentialsProvider 
                환경변수방식

            - SystemPropertyCredentialsProvider 
                시스템 프로퍼티 방식 ex) java -jar -XXX.accessKeyId=ACCESS_KEY ...

            - ProfileCredentialsProvider 
                프로필 방식 ex) ~/.aws/credentials에 AWS CLI가 생성해 둔 설정 파일 읽어 로그인

            - InstanceProfileCredentialsProvider / WebIdentifyTokenFileCredentialsProvider
                EC2 인스턴스나 쿠버네티스 환경에서 사용됨
                애플리케이션에 키를 아예 주입하지 않고 서버(인프라) 자체에 권한을 부여(IAM Role)하면 SDK가 서버와 통신하며 임시 토큰을 받아 로그인
            */
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)
                )
            )

            /*
            가상 호스트 스타일(bucket.localhost) 대신 path 스타일 (localhost/bucket) 주소 형식을 사용 강제
            MINIO를 사용하기 위해선 필수
            AWS는 2020년 9월 이후 생성된 모든 s3 버킷에 대해 Path-style 지원을 중단했음 따라서 지금 AWS-S3에 forcePathStyle(true)를 먹이면 에러 뱉음. 반드시 가상 호스트 스타일을 사용해야함
            Cloudflare R2, MiniIO의 경우 아직 사용 가능함
            */
            .forcePathStyle(true)
            
            // 타임아웃, 재시도 횟수 등 SDK 클라이언트의 추가적인 고급 옵션을 재정의
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    // 요청 전체에 대한 총 타임아웃 (연결 + 데이터 전송 등 총합 제한시간)
                    .apiCallTimeout(Duration.ofSeconds(30))

                    // 단일 HTTP 요청/응답 한 건에 대한 타임아웃
                    .apiCallAttemptTimeout(Duration.ofSeconds(10))

                    // 재시도 정책
                    .retryStrategy(retryStrategy)

                    // HTTP 헤더 조작
                    .putHeader("X-Custom-Header", "my-s3-lab-app")
                    .build()
            )
            .build();
    }
}
