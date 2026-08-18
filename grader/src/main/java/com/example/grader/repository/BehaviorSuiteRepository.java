package com.example.grader.repository;

import com.example.grader.entity.BehaviorSuite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BehaviorSuiteRepository extends JpaRepository<BehaviorSuite, String> {
    Optional<BehaviorSuite> findBySuiteCode(String suiteCode);
    boolean existsBySuiteCode(String suiteCode);
    List<BehaviorSuite> findAllByOrderByUpdatedAtDesc();
    List<BehaviorSuite> findByExamIdOrderByUpdatedAtDesc(String examId);
}
