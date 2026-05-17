package com.example.grader.controller;

import com.example.grader.service.BatchGradingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/batch")
@CrossOrigin(origins = "*")
public class BatchController {

    @Autowired
    private BatchGradingService batchService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "examId",     defaultValue = "FLUTTER_PE_01") String examId,
            @RequestParam(value = "createdBy",  defaultValue = "admin") String createdBy) {

        if (files == null || files.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Không có file"));

        try {
            return ResponseEntity.ok(batchService.enqueueBatch(files, examId, createdBy));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/progress/{batchId}")
    public ResponseEntity<?> getProgress(@PathVariable String batchId) {
        try {
            return ResponseEntity.ok(batchService.getBatchProgress(batchId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
