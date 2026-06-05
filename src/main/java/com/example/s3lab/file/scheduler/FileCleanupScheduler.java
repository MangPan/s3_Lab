package com.example.s3lab.file.scheduler;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.s3lab.file.dto.ExpiredFileResponse;
import com.example.s3lab.file.service.FileService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FileCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(FileCleanupScheduler.class);
    private final FileService fileService;

    /**
     * 주기적으로 실행될 스케줄러 메소드
     * application.properties의 file.expire-schedule-ms의 값을 가져옴
     */
    @Scheduled(fixedDelayString = "${file.expire-schedule-ms}")
    public void expirePendingFiles() {
        try {
            // 배치 단위로 루프를 돌며 만료된 파일 처리 후 그 결과를 받아옴
            List<ExpiredFileResponse> expiredFiles = fileService.expirePendingFiles();

            // 실제로 만료된 파일이 있다면 로그 출력
            if (!expiredFiles.isEmpty()) {
                log.info("Expired pending files. count={}", expiredFiles.size());
            }
        } catch (RuntimeException exception) {
            log.info("Failed to expire pending files.", exception);
        }
    }
}
