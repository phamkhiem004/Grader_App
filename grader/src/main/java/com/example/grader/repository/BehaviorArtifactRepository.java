package com.example.grader.repository;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BehaviorArtifactRepository extends JpaRepository<BehaviorArtifact, String> {
    List<BehaviorArtifact> findBySuiteIdOrderByArtifactTypeAscVersionDesc(String suiteId);
    Optional<BehaviorArtifact> findFirstBySuiteIdAndArtifactTypeAndActiveTrueOrderByVersionDesc(
            String suiteId, BehaviorArtifactType artifactType);
    List<BehaviorArtifact> findBySuiteIdAndArtifactTypeAndActiveTrue(
            String suiteId, BehaviorArtifactType artifactType);
    long countBySuiteIdAndArtifactType(String suiteId, BehaviorArtifactType artifactType);
}
