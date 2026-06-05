package com.example.s3lab.file.scheduler;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.s3lab.file.dto.ExpiredFileResponse;
import com.example.s3lab.file.service.FileService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FileCleanupScheduler {

    private final FileService fileService;

    @Scheduled(fixedDelayString = "${file.expire-schedule-ms}")    
    public void expirePendingFiles(){
        List<ExpiredFileResponse> expiredFiles = fileService.expirePendingFiles();
        if(!expiredFiles.isEmpty()){
            System.out.println("[FileCleanupScheduler] expiredFiles=" + expiredFiles.size());
        }
    }
}
