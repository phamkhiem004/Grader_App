package com.example.grader.entity;

/**
 * CÂU TRẢ LỜI DUY NHẤT mà người chấm cần: lượt chấm này có cho ra điểm tin được không?
 *
 * <p>Trước đây màn hình phải tự suy điều đó từ bộ ba {@code status × diagnostic_origin ×
 * diagnostic_code} — 34 mã lỗi trộn với 6 trạng thái — nên sự cố hạ tầng và bài sinh viên làm sai
 * hiện lên giống hệt nhau. Nhãn này thu về 4 giá trị và giữ một BẤT BIẾN:
 * <b>{@code SCORED} ⟺ {@code score != null}</b>.
 *
 * <p>Suy được hoàn toàn từ {@link GradingStatus} nên không cần thêm cột DB: điều thực sự đổi nằm ở
 * chỗ ghi trạng thái — lỗi quy được cho bài sinh viên nay ghi {@code DONE} 0 điểm thay vì
 * {@code ERROR}, nên chỉ còn sự cố THẬT của hệ thống đọng lại ở {@link #SYSTEM_BLOCKED}.
 */
public enum GradingOutcome {

    /** Đang chờ hoặc đang chấm — chưa có gì để nói. */
    PENDING,

    /** Có điểm tin được, KỂ CẢ 0 điểm do bài làm sai. Người chấm không phải làm gì. */
    SCORED,

    /** Máy chấm không cho ra điểm (testcase / môi trường / chưa xác định) — cần người can thiệp. */
    SYSTEM_BLOCKED,

    /** Người dùng đã dừng phiên; bài chưa từng được chấm nên không có gì để quy trách nhiệm. */
    STOPPED;

    public static GradingOutcome of(GradingStatus status) {
        if (status == null) return PENDING;
        return switch (status) {
            case QUEUED, GRADING -> PENDING;
            case DONE            -> SCORED;
            case CANCELLED       -> STOPPED;
            // ERROR giờ chỉ còn trong dữ liệu CŨ: mọi lỗi quy được cho bài sinh viên đều đã
            // chuyển sang DONE 0 điểm, nên hai trạng thái này chỉ còn nghĩa "máy chưa cho kết quả".
            case ERROR, MANUAL_REVIEW -> SYSTEM_BLOCKED;
        };
    }
}
