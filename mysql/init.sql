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
    result_json   LONGTEXT                       COMMENT 'JSON đầy đủ (student/exam/test_cases/analyze)',
    error_log     LONGTEXT                       COMMENT 'Lỗi thô khi chấm',
    feedback_json LONGTEXT                       COMMENT 'Cache nhận xét từ feedback-bot',
    feedback_src_hash VARCHAR(40)                COMMENT 'Hash result_json lúc sinh feedback',
    manual_score  FLOAT                          COMMENT 'Điểm chấm tay',
    manual_json   LONGTEXT                       COMMENT 'JSON tiêu chí và nhận xét chấm tay',
    manual_by     VARCHAR(100)                   COMMENT 'Email GV chấm tay',
    manual_at     TIMESTAMP                      COMMENT 'Thời điểm chấm tay',
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
    exam_id       VARCHAR(50)   NOT NULL          COMMENT 'VD: FLUTTER_PE_01',
    exam_name     VARCHAR(200)                   COMMENT 'Tên đề thi hiển thị',
    teacher_note  TEXT                           COMMENT 'Ghi chú/đề bài để đối chiếu khi xem kết quả',
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
    testcase_config_json LONGTEXT                COMMENT 'Cấu hình testcase template-instance',
    testcase_version INT                         COMMENT 'Phiên bản cấu hình testcase',
    testcase_status VARCHAR(20)                  COMMENT 'DRAFT hoặc PUBLISHED',
    testcase_published_at TIMESTAMP              COMMENT 'Thời điểm publish testcase',

    UNIQUE KEY uq_exams_exam_id (exam_id),
    INDEX idx_exam_status (status),
    INDEX idx_exam_has_results (has_results)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Thông tin và trạng thái từng đề thi';


-- ── Bảng 3: Lịch sử batch upload ────────────────────────────────
CREATE TABLE IF NOT EXISTS grading_batches (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id      VARCHAR(80)   NOT NULL,
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

    UNIQUE KEY uq_grading_batches_batch_id (batch_id),
    INDEX idx_batch_exam (exam_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Lịch sử các lần upload hàng loạt';


-- ── Bảng 4 (ĐÃ BỎ): teachers ────────────────────────────────────
-- App không còn đăng nhập/đăng ký/role. DB cũ đã tạo bảng này thì xóa tay:
--     DROP TABLE IF EXISTS teachers;
-- Không có FK nào trỏ tới nó (created_by/manual_by chỉ là VARCHAR) nên xóa an toàn.


-- ── Bảng 5: Category năng lực (syllabus) ────────────────────────
-- Lưu ý: SyllabusService tự SEED dữ liệu từ resources/syllabus.json khi DB rỗng,
-- và Hibernate (ddl-auto=update) tự tạo/đồng bộ bảng. Định nghĩa dưới đây chỉ để
-- tài liệu hóa & cài mới sạch.
CREATE TABLE IF NOT EXISTS skill_category (
    code             VARCHAR(40)  PRIMARY KEY              COMMENT 'ID ổn định: DART, UI, VALIDATION...',
    name             VARCHAR(120) NOT NULL                 COMMENT 'Tên đầy đủ',
    competency_label VARCHAR(80)                           COMMENT 'Nhãn năng lực: Code Dart, Giao diện, Validate',
    description      TEXT,
    display_order    INT          DEFAULT 0,
    weak_threshold   DOUBLE       DEFAULT 0.4              COMMENT 'ratio < weak => YẾU',
    good_threshold   DOUBLE       DEFAULT 0.7              COMMENT 'ratio >= good => TỐT',
    active           BOOLEAN      DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Category năng lực (mảng kiến thức lớn)';

-- ── Bảng 6: Skill chi tiết (syllabus) ───────────────────────────
CREATE TABLE IF NOT EXISTS skill (
    code               VARCHAR(60)  PRIMARY KEY            COMMENT 'ID ổn định: DART_LOGIC, UI_BASIC...',
    category_code      VARCHAR(40)  NOT NULL,
    name               VARCHAR(120) NOT NULL,
    description        TEXT,
    default_difficulty VARCHAR(20)  DEFAULT 'basic'        COMMENT 'basic | intermediate | advanced',
    testable           VARCHAR(10)  DEFAULT 'auto'         COMMENT 'auto | manual (cần package ngoài/mạng)',
    resources_json     TEXT                                COMMENT 'JSON array học liệu',
    display_order      INT          DEFAULT 0,
    deprecated         BOOLEAN      DEFAULT FALSE          COMMENT 'Xóa mềm: đề cũ vẫn map được',

    INDEX idx_skill_category (category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Kỹ năng chi tiết (testcase trỏ vào đây bằng skill_code)';

-- ── Bảng 7: Version syllabus đã seed (1 dòng, id=1) ─────────────
CREATE TABLE IF NOT EXISTS syllabus_meta (
    id          BIGINT      PRIMARY KEY              COMMENT 'Luôn = 1',
    version     VARCHAR(20)                          COMMENT 'Version syllabus đã áp dụng',
    updated_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Đánh dấu version syllabus để re-seed khi đổi';


-- ── Dữ liệu mẫu để test ─────────────────────────────────────────
INSERT IGNORE INTO exams (exam_id, exam_name, image_name, status, created_by)
VALUES (
    'FLUTTER_PE_01',
    'Practical Exam - Flutter cơ bản',
    'grading-env-flutter_pe_01',
    'READY',
    'giaovien@fpt.edu.vn'
);
