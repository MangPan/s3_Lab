// package com.example.s3lab.file.repository;

// import java.time.Instant;
// import java.util.ArrayList;
// import java.util.Comparator;
// import java.util.List;
// import java.util.Optional;
// import java.util.concurrent.ConcurrentHashMap;

// import org.springframework.stereotype.Repository;

// import com.example.s3lab.domain.FileRecord;
// import com.example.s3lab.domain.FileStatus;

// @Repository
// public class FileRecordRepository {
//     private final ConcurrentHashMap<String, FileRecord> store = new ConcurrentHashMap<>();

//     public FileRecord save(FileRecord fileRecord){
//         store.put(fileRecord.getId(), fileRecord);
//         return fileRecord;
//     }

//     public Optional<FileRecord> findById(String id){
//         return Optional.ofNullable(store.get(id));
//     }

//     public List<FileRecord> findAll(){
//         return new ArrayList<>(store.values());
//     }

//     public List<FileRecord> findByStatus(FileStatus status){
//         return store.values()
//             .stream()
//             .filter(fileRecord -> (fileRecord.getStatus() == status))
//             .toList();
//     }

//     /**
//      * 만료 대상 PENDING 파일중
//      * 가장 오래된 것 부터
//      * 최대 limit개 만큼만 가져옴 
//      */
//     public List<FileRecord> findExpiredPendingFiles(
//         Instant cutoff,
//         int limit
//     ){
//         return store.values()
//             .stream()
//             .filter(fileRecord -> fileRecord.getStatus() == FileStatus.PENDING)
//             .filter(fileRecord -> fileRecord.getCreatedAt().isBefore(cutoff))
//             .sorted(Comparator.comparing(FileRecord::getCreatedAt))
//             .limit(limit)
//             .toList();

//     }
// }

package com.example.s3lab.file.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.s3lab.domain.FileRecord;
import com.example.s3lab.domain.FileStatus;
/**
 * FileRecord Entity Repository
 */
public interface FileRecordRepository extends JpaRepository<FileRecord, String> {

    /**
     * select * from file_records
     * where status = ?
     * order by created_at desc;
     */
    List<FileRecord> findByStatusOrderByCreatedAtDesc(FileStatus status);

    /**
     * select * from file_records
     * where status = ? and created_at < ?
     * order by created_at ASC
     * Limit ?; // pageable을 통해 한 번에 가져올 데이터 개수를 제어
     */
    List<FileRecord> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            FileStatus status,
            Instant createdAt,
            Pageable pageable); // 여기선 배치 크기를 조절하기 위해 사용됨
}