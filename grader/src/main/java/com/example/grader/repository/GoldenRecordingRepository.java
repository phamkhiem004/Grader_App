package com.example.grader.repository;

import com.example.grader.entity.GoldenRecording;
import com.example.grader.entity.RecordingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface GoldenRecordingRepository extends JpaRepository<GoldenRecording, String> {
    List<GoldenRecording> findBySuiteIdOrderByStartedAtDesc(String suiteId);
    long countBySuiteIdAndStatus(String suiteId, RecordingStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recording from GoldenRecording recording where recording.id = :id")
    Optional<GoldenRecording> findByIdForUpdate(@Param("id") String id);
}
