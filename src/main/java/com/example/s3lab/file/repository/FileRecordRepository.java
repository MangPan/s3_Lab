package com.example.s3lab.file.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import com.example.s3lab.domain.FileRecord;
import com.example.s3lab.domain.FileStatus;

@Repository
public class FileRecordRepository {
    private final ConcurrentHashMap<String, FileRecord> store = new ConcurrentHashMap<>();

    public FileRecord save(FileRecord fileRecord){
        store.put(fileRecord.getId(), fileRecord);
        return fileRecord;
    }

    public Optional<FileRecord> findById(String id){
        return Optional.ofNullable(store.get(id));
    }

    public List<FileRecord> findAll(){
        return new ArrayList<>(store.values());
    }

    public List<FileRecord> findByStatus(FileStatus status){
        return store.values()
            .stream()
            .filter(fileRecord -> (fileRecord.getStatus() == status))
            .toList();
    }

    /**
     * 만료 대상 PENDING 파일중
     * 가장 오래된 것 부터
     * 최대 limit개 만큼만 가져옴 
     */
    public List<FileRecord> findExpiredPendingFiles(
        Instant cutoff,
        int limit
    ){
        return store.values()
            .stream()
            .filter(fileRecord -> fileRecord.getStatus() == FileStatus.PENDING)
            .filter(fileRecord -> fileRecord.getCreatedAt().isBefore(cutoff))
            .sorted(Comparator.comparing(FileRecord::getCreatedAt))
            .limit(limit)
            .toList();

    }
}
