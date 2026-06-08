package com.example.grader.repository;

import com.example.grader.entity.SkillCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillCategoryRepository extends JpaRepository<SkillCategory, String> {
    List<SkillCategory> findAllByOrderByDisplayOrderAsc();
}
