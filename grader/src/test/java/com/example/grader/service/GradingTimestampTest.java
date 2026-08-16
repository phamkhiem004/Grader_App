package com.example.grader.service;

import com.example.grader.entity.ExamResult;
import com.example.grader.entity.GradingStatus;
import com.example.grader.repository.ExamResultRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hai mốc "bắt đầu chấm" / "chấm xong" là cột của bảng điểm xuất ra, nên phải được ghi đúng ngay
 * tại chỗ đổi trạng thái.
 *
 * <p>Không dùng được {@code submittedAt} (lúc bài vào hàng đợi, có thể chờ hàng chục phút) hay
 * {@code updatedAt} (chấm tay cũng đổi nó) để suy ra hai mốc này — đó là lý do phải có cột riêng,
 * và là thứ test này canh.
 */
class GradingTimestampTest {

    private record Fixture(BatchGradingService service, ExamResult row, Method updateStatus) {
        void moveTo(GradingStatus status) throws Exception {
            updateStatus.invoke(service,
                    new BatchGradingService.GradingJob("HE180412", "HE180412", "B1", "PE_01", "x.zip", null),
                    status, null, null, null);
        }
    }

    private Fixture fixture() throws Exception {
        ExamResult row = new ExamResult();
        ExamResultRepository repo = mock(ExamResultRepository.class);
        when(repo.findByStudentIdAndBatchId("HE180412", "B1")).thenReturn(Optional.of(row));

        BatchGradingService service = new BatchGradingService();
        ReflectionTestUtils.setField(service, "resultRepo", repo);

        Method method = BatchGradingService.class.getDeclaredMethod("updateStatus",
                BatchGradingService.GradingJob.class, GradingStatus.class,
                Float.class, String.class, String.class);
        method.setAccessible(true);
        return new Fixture(service, row, method);
    }

    @Test
    void ghiMocBatDauKhiVaoMayVaMocKetThucKhiChamXong() throws Exception {
        Fixture f = fixture();
        assertNull(f.row().getGradingStartedAt(), "Bài mới xếp hàng thì chưa có mốc nào");

        f.moveTo(GradingStatus.GRADING);
        Instant started = f.row().getGradingStartedAt();
        assertNotNull(started, "Vào máy chấm phải ghi mốc bắt đầu");
        assertNull(f.row().getGradingFinishedAt(), "Đang chấm thì chưa thể có mốc kết thúc");

        f.moveTo(GradingStatus.DONE);
        assertEquals(started, f.row().getGradingStartedAt(), "Mốc bắt đầu không được ghi đè lúc xong");
        assertNotNull(f.row().getGradingFinishedAt());
        assertFalse(f.row().getGradingFinishedAt().isBefore(started), "Chấm xong không thể trước lúc bắt đầu");
    }

    @Test
    void moiKetCucCuaLuotChamDeuChotMocKetThuc() throws Exception {
        for (GradingStatus terminal : new GradingStatus[]{
                GradingStatus.DONE, GradingStatus.ERROR, GradingStatus.MANUAL_REVIEW}) {
            Fixture f = fixture();
            f.moveTo(GradingStatus.GRADING);
            f.moveTo(terminal);
            assertNotNull(f.row().getGradingFinishedAt(), terminal + " cũng là chấm xong, phải có mốc");
        }
    }

    /** Chấm lại: mốc kết thúc CŨ phải biến mất, nếu không bảng điểm hiện "xong trước khi bắt đầu". */
    @Test
    void chamLaiXoaMocKetThucCuaLuotTruoc() throws Exception {
        Fixture f = fixture();
        f.moveTo(GradingStatus.GRADING);
        f.moveTo(GradingStatus.DONE);
        Instant firstRun = f.row().getGradingFinishedAt();
        assertNotNull(firstRun);

        f.moveTo(GradingStatus.GRADING);
        assertNull(f.row().getGradingFinishedAt(), "Lượt chấm mới chưa xong mà vẫn còn mốc kết thúc cũ");
        assertTrue(f.row().getGradingStartedAt().compareTo(firstRun) >= 0,
                "Mốc bắt đầu phải là của lượt MỚI");
    }
}
