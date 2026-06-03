package com.example.s3lab.file.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import com.example.s3lab.domain.FileRecord;

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
}
