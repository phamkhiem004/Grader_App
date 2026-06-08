package com.example.grader.repository;

import com.example.grader.entity.Skill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillRepository extends JpaRepository<Skill, String> {
    List<Skill> findAllByOrderByDisplayOrderAsc();
    List<Skill> findByCategoryCodeOrderByDisplayOrderAsc(String categoryCode);
}
