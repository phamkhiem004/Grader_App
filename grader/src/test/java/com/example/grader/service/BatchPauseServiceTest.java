package com.example.grader.service;

import com.example.grader.dto.BatchProgressResponse;
import com.example.grader.entity.BatchStatus;
import com.example.grader.entity.GradingBatch;
import com.example.grader.entity.GradingStatus;
import com.example.grader.repository.ExamResultRepository;
import com.example.grader.repository.GradingBatchRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchPauseServiceTest {

    @Test
    void pauseAndResumeArePersistedAndReturnedInProgress() throws Exception {
        GradingBatchRepository batchRepo = mock(GradingBatchRepository.class);
        ExamResultRepository resultRepo = mock(ExamResultRepository.class);
        BatchGradingService service = new BatchGradingService();
        inject(service, "batchRepo", batchRepo);
        inject(service, "resultRepo", resultRepo);

        GradingBatch batch = new GradingBatch();
        batch.setBatchId("BATCH_TEST");
        batch.setExamId("PE_TEST");
        batch.setStatus(BatchStatus.IN_PROGRESS);
        when(batchRepo.findByBatchId("BATCH_TEST")).thenReturn(Optional.of(batch));
        when(resultRepo.findRowsByBatchId("BATCH_TEST")).thenReturn(List.of());
        for (GradingStatus status : GradingStatus.values()) {
            when(resultRepo.countByBatchIdAndStatus("BATCH_TEST", status)).thenReturn(0L);
        }

        BatchProgressResponse paused = service.pauseBatch("BATCH_TEST");
        assertEquals(BatchStatus.PAUSED, batch.getStatus());
        assertEquals("PAUSED", paused.getStatus());

        BatchProgressResponse resumed = service.resumeBatch("BATCH_TEST");
        assertEquals(BatchStatus.IN_PROGRESS, batch.getStatus());
        assertEquals("IN_PROGRESS", resumed.getStatus());
        verify(batchRepo, org.mockito.Mockito.times(2)).save(batch);
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
