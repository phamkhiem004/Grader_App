package com.example.grader.repository;

import com.example.grader.entity.GoldenApp;
import com.example.grader.entity.GoldenAppStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GoldenAppRepository extends JpaRepository<GoldenApp, String> {
    List<GoldenApp> findAllByOrderByUpdatedAtDesc();
    List<GoldenApp> findByExamIdOrderByUpdatedAtDesc(String examId);
    List<GoldenApp> findByStatusOrderByUpdatedAtDesc(GoldenAppStatus status);
}
