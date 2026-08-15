package com.example.grader.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GradingOutcomeTest {

    @Test
    void scoredCoversEveryStateThatCarriesAScore() {
        // 0 điểm do bài làm sai giờ là DONE, nên nó phải rơi vào SCORED — người chấm không
        // phải nhìn thấy nó nữa.
        assertEquals(GradingOutcome.SCORED, GradingOutcome.of(GradingStatus.DONE));
    }

    @Test
    void everyStateWithoutAScoreIsReportedToTheGrader() {
        // Hai trạng thái này chỉ còn nghĩa "máy chấm chưa cho kết quả". ERROR còn lại ở dữ liệu
        // cũ nhưng cũng không có điểm, nên xếp chung là đúng chứ không phải tạm bợ.
        assertEquals(GradingOutcome.SYSTEM_BLOCKED, GradingOutcome.of(GradingStatus.MANUAL_REVIEW));
        assertEquals(GradingOutcome.SYSTEM_BLOCKED, GradingOutcome.of(GradingStatus.ERROR));
    }

    @Test
    void stoppedAndPendingAreNotIncidents() {
        assertEquals(GradingOutcome.STOPPED, GradingOutcome.of(GradingStatus.CANCELLED));
        assertEquals(GradingOutcome.PENDING, GradingOutcome.of(GradingStatus.QUEUED));
        assertEquals(GradingOutcome.PENDING, GradingOutcome.of(GradingStatus.GRADING));
        assertEquals(GradingOutcome.PENDING, GradingOutcome.of(null));
    }
}
