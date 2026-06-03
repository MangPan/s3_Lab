package com.example.s3lab.file.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.example.s3lab.file.dto.FileObjectResponse;
import com.example.s3lab.file.dto.FileUploadResponse;
import com.example.s3lab.file.service.FileService;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor // final 필드 대상 생성자 생성 (Lombok)
public class FileController {

    private final FileService fileService;

    /**
     * 파일 업로드 API (POST /files)
     * HTTP Multipart Form Data 형식으로 파일을 받아 S3에 저장한다.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileUploadResponse upload(@RequestParam MultipartFile file){
        return fileService.upload(file);
    }

    /**
     * 파일 목록 조회 API (GET /files)
     * 버킷에 저장된 모든 파일의 메타데이터 목록을 JSON 배열로 반환한다.
     */
    @GetMapping
    public List<FileObjectResponse> list(){
        return fileService.list();
    }


    /**
     * 파일 다운로드 API (GET /files/download)
     * 특정 Key에 해당하는 파일을 S3에서 다운로드하여 브라우저에 파일 바이너리를 전송한다.
     */
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam String key) {
        //Service로부터 S3 오브젝트 데이터를 바이트 배열 래퍼 형태로 가져옴
        ResponseBytes<GetObjectResponse> responseBytes = fileService.download(key);
        GetObjectResponse objectResponse = responseBytes.response();

        // S3 Key(uploads/UUID-파일명.ext)에서 순수 파일명(파일명.ext)만 추출
        String filename = extractFilename(key);

        // 다운로드 시 한글 깨짐 방지 및 공백 문자가 '+'로 치환되는 현상을 '%20'으로 보정하는 인코딩 작업
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
            .replace("+", "%20");

        // HTTP 응답 헤더 및 바디 구성
        return ResponseEntity.ok()
            // S3에 저장되어 있던 원래 파일의 컨텐츠 타입 그대로 지정
            .contentType(MediaType.parseMediaType(objectResponse.contentType()))
            // 브라우저가 화면에 띄우지 안고 '파일 다운로드' 대화상자를 열도록 Content-Disposition 헤더 설정
            // RFC 5987 표준 규격
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + encodedFilename
            )
            // 실제 바이너리 데이터를 바디에 담아서 전송
            .body(responseBytes.asByteArray());
    }

    /**
     * 파일 삭제 API (DELETE /files)
     * 특정 Key를 전달받아 S3 버킷 내의 해당 파일을 영구 삭제하고 204 No Content를 반환
     */
    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestParam String key){
        fileService.delete(key);

        return ResponseEntity.noContent().build();
    }


    /**
     * S3 Key 경로에서 파일명만 추출하는 헬퍼 메서드
     * ex) "uploads/something/path/ec8c899a-...-test.txt" -> "ec8c899a-...-test.txt"
     */
    private String extractFilename(String key){
        int slashIndex = key.lastIndexOf("/");

        // 경로 구분자가 없다면 Key 자체가 파일명이므로 그대로 반환
        if(slashIndex == -1){
            return key;
        }
        
        // 가장 마지막 슬래시 다음 위치부터 끝까지 문자열을 잘라내어 파일명만 추출
        return key.substring(slashIndex + 1);
    }
    
}
