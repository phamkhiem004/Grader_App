-- mysql/init.sql

CREATE DATABASE IF NOT EXISTS chamthi_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE chamthi_db;

-- ── Bảng 1: Kết quả chấm bài ────────────────────────────────────
CREATE TABLE IF NOT EXISTS exam_results (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id    VARCHAR(20)   NOT NULL        COMMENT 'Mã SV, VD: SE123456',
    student_name  VARCHAR(100)                  COMMENT 'Tên SV parse từ tên file zip',
    exam_id       VARCHAR(50)   NOT NULL        COMMENT 'Mã đề thi, VD: FLUTTER_PE_01',
    batch_id      VARCHAR(60)                   COMMENT 'ID lần upload hàng loạt',
    score         FLOAT                         COMMENT 'Điểm hệ 10',
    status        ENUM(
                    'QUEUED',   -- Đang chờ trong queue
                    'GRADING',  -- Đang chạy Docker
                    'DONE',     -- Chấm xong
                    'ERROR'     -- Lỗi compile hoặc hệ thống
                  ) DEFAULT 'QUEUED',
    mode          VARCHAR(10)   DEFAULT 'submit' COMMENT 'submit | test',
    details       LONGTEXT                       COMMENT 'JSON chi tiết từng testcase',
    result_json   LONGTEXT                       COMMENT 'JSON đầy đủ (student/exam/test_cases/analyze) cho AI',
    error_log     LONGTEXT                          COMMENT 'Lỗi thô để AI đọc sau',
    submitted_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
                                ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_batch      (batch_id),
    INDEX idx_student    (student_id),
    INDEX idx_exam       (exam_id),
    INDEX idx_status     (status),
    UNIQUE KEY uq_student_exam_mode (student_id, exam_id, mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Kết quả chấm bài từng SV';


-- ── Bảng 2: Thông tin đề thi ─────────────────────────────────────
CREATE TABLE IF NOT EXISTS exams (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_id       VARCHAR(50)   NOT NULL UNIQUE  COMMENT 'VD: FLUTTER_PE_01',
    exam_name     VARCHAR(200)                   COMMENT 'Tên đề thi hiển thị',
    teacher_note  TEXT                           COMMENT 'Ghi chú/đề bài cho AI hiểu ngữ cảnh',
    image_name    VARCHAR(100)                   COMMENT 'Ảnh chấm (grading-base khi dùng mount)',
    testcase_path VARCHAR(500)                   COMMENT 'Đường dẫn testcase trên host để mount lúc chấm',
    has_results   BOOLEAN       NOT NULL DEFAULT FALSE COMMENT 'Flag Pattern: đã có bài chấm xong → hiện ở dropdown Thống kê',
    status        ENUM(
                    'BUILDING', -- Đang build Docker image
                    'READY',    -- Sẵn sàng chấm
                    'DISABLED'  -- Đã đóng
                  ) DEFAULT 'BUILDING',
    allowed_packages TEXT                        COMMENT 'JSON danh sách package SV được dùng',
    created_by    VARCHAR(100)                   COMMENT 'Email GV tạo đề',
    created_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
                                ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_exam_status (status),
    INDEX idx_exam_has_results (has_results)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Thông tin và trạng thái từng đề thi';


-- ── Bảng 3: Lịch sử batch upload ────────────────────────────────
CREATE TABLE IF NOT EXISTS grading_batches (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id      VARCHAR(60)   NOT NULL UNIQUE,
    exam_id       VARCHAR(50)   NOT NULL,
    total_files   INT           DEFAULT 0        COMMENT 'Tổng số bài upload',
    done_count    INT           DEFAULT 0        COMMENT 'Số bài đã chấm xong',
    error_count   INT           DEFAULT 0        COMMENT 'Số bài lỗi',
    status        ENUM(
                    'IN_PROGRESS',
                    'COMPLETED',
                    'PARTIAL'   -- Có 1 số bài lỗi
                  ) DEFAULT 'IN_PROGRESS',
    created_by    VARCHAR(100)                   COMMENT 'Email GV upload',
    created_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    completed_at  TIMESTAMP                      COMMENT 'Thời điểm chấm xong toàn bộ',

    INDEX idx_batch_exam (exam_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Lịch sử các lần upload hàng loạt';


-- ── Dữ liệu mẫu để test ─────────────────────────────────────────
INSERT IGNORE INTO exams (exam_id, exam_name, image_name, status, created_by)
VALUES (
    'FLUTTER_PE_01',
    'Practical Exam - Flutter cơ bản',
    'grading-env-flutter_pe_01',
    'READY',
    'giaovien@fpt.edu.vn'
);