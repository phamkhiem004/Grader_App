package com.example.grader.repository;

import com.example.grader.entity.TestcaseTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestcaseTemplateRepository extends JpaRepository<TestcaseTemplate, String> {
    List<TestcaseTemplate> findAllByOrderByCreatedAtAsc();
}
