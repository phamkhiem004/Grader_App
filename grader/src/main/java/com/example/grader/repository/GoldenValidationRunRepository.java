package com.example.grader.repository;

import com.example.grader.entity.GoldenValidationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GoldenValidationRunRepository extends JpaRepository<GoldenValidationRun, String> {
    List<GoldenValidationRun> findBySuiteIdOrderByCreatedAtDesc(String suiteId);
    Optional<GoldenValidationRun> findFirstBySuiteIdOrderByCreatedAtDesc(String suiteId);
}
