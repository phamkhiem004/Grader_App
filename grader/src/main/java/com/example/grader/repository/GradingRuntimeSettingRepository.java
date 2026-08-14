package com.example.grader.repository;

import com.example.grader.entity.GradingRuntimeSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GradingRuntimeSettingRepository extends JpaRepository<GradingRuntimeSetting, Long> {
}
