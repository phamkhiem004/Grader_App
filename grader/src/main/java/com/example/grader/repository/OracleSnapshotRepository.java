package com.example.grader.repository;

import com.example.grader.entity.OracleSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OracleSnapshotRepository extends JpaRepository<OracleSnapshot, String> {
    List<OracleSnapshot> findByScenarioIdOrderByCreatedAtDesc(String scenarioId);
    Optional<OracleSnapshot> findFirstByScenarioIdOrderByCreatedAtDesc(String scenarioId);
    Optional<OracleSnapshot> findFirstByScenarioIdAndSeedOrderByCreatedAtDesc(String scenarioId, String seed);
    void deleteByScenarioId(String scenarioId);
}
