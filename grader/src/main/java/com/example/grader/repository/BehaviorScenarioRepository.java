package com.example.grader.repository;

import com.example.grader.entity.BehaviorScenario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BehaviorScenarioRepository extends JpaRepository<BehaviorScenario, String> {
    List<BehaviorScenario> findBySuiteIdOrderByDisplayOrderAscCreatedAtAsc(String suiteId);
    Optional<BehaviorScenario> findBySuiteIdAndScenarioCode(String suiteId, String scenarioCode);
    Optional<BehaviorScenario> findFirstBySourceRecordingId(String sourceRecordingId);
    long countBySuiteIdAndEnabledTrue(String suiteId);
}
