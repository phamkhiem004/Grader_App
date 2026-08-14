package com.example.grader.controller;

import com.example.grader.config.AppActor;
import com.example.grader.dto.BatchSubmitResponse;
import com.example.grader.service.BatchGradingService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BatchControllerFolderUploadTest {

    @Test
    void bindsEachZipToItsUsernameFolderRegardlessOfFileName() throws Exception {
        BatchGradingService service = mock(BatchGradingService.class);
        when(service.enqueueBatch(anyList(), eq(List.of("khiempghe186137")),
                eq("PE_01"), eq(AppActor.DEFAULT)))
                .thenReturn(new BatchSubmitResponse("BATCH_01", 1, List.of()));
        BatchController controller = new BatchController();
        ReflectionTestUtils.setField(controller, "batchService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        MockMultipartFile solution = new MockMultipartFile(
                "files", "bai_lam_bat_ky.zip", "application/zip", new byte[]{1, 2, 3});

        mvc.perform(multipart("/api/batch/upload")
                        .file(solution)
                        .param("usernames", "khiempghe186137")
                        .param("examId", "PE_01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("BATCH_01"))
                .andExpect(jsonPath("$.totalQueued").value(1));

        verify(service).enqueueBatch(anyList(), eq(List.of("khiempghe186137")),
                eq("PE_01"), eq(AppActor.DEFAULT));
    }
}
