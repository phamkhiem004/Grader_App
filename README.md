# Grader App — Hệ thống chấm bài thi Flutter tự động

Chấm tự động hàng loạt bài thi thực hành Flutter của sinh viên trong môi trường
Docker cô lập. Giảng viên upload testcase một lần, sau đó nộp hàng loạt file ZIP
bài làm và nhận điểm + chi tiết từng testcase, xuất CSV.

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────────────┐
│  Frontend   │ ──▶ │  Spring Boot API  │ ──▶ │  Docker (chấm cô lập)   │
│  Next.js    │     │  (grader/)        │     │  grading-base → đề thi  │
└─────────────┘     └────────┬─────────┘     └─────────────────────────┘
                             │
                        ┌────▼────┐
                        │  MySQL  │
                        └─────────┘
```

## Thành phần

| Thư mục        | Vai trò |
|----------------|---------|
| `frontend/`    | UI Next.js 16 + React 19 + Tailwind 4 (dashboard, cấu hình đề, thống kê) |
| `grader/`      | Backend Spring Boot: nhận upload, build ảnh, điều phối chấm, lưu kết quả |
| `grader-base/` | Dockerfile + pubspec + script dùng chung để dựng môi trường chấm |
| `mysql/`       | `init.sql` khởi tạo schema |
| `exams/`       | (runtime) testcase đã upload, mount lúc chấm — **không commit** |
| `submissions/` | (runtime) zip bài nộp SV lưu để audit — **không commit** |

## ⚡ Kiến trúc: 1 ảnh nền + mount (không build image cho từng đề)

Phần nặng (Flutter + packages + engine) gói **một lần** vào ảnh nền `grading-base`.
Setup đề **không build image** — chỉ lưu testcase lên đĩa. Lúc chấm, **mount** testcase + bài SV
vào ảnh nền qua container tạm (`--rm`).

```
grading-base:latest        ← ảnh DUY NHẤT (build 1 lần)
   docker run --rm -v <bài SV>:/app/lib -v <testcase đề>:/app/test  grading-base
```

- **Upload đề: gần như tức thì** (không có `docker build`).
- **Không tích tụ image** cho từng đề → Docker gọn.
- **Chấm bài** chỉ tạo container tạm, tự xóa (`--rm`) — không để lại gì.

> Đề cũ đã build image `grading-env-*` (legacy) vẫn chấm được; setup lại để chuyển sang mount.

Backend **tự build ảnh nền lần đầu** (khi GV setup đề đầu tiên). Hoặc build trước thủ công:

```powershell
# Windows
cd grader-base; ./build-base.ps1
```
```bash
# Linux / macOS
cd grader-base && ./build-base.sh
```

> Đổi `grader-base/pubspec.base.yaml` (thêm package cho SV dùng) → build lại ảnh nền.

## Chạy dự án (khuyến nghị: backend trên host)

Backend build & mount thư mục bài nộp vào Docker của host nên nên chạy **trực tiếp trên host**.

```bash
# 1) Hạ tầng MySQL
docker compose up -d

# 2) Backend (cửa sổ riêng) — chạy từ thư mục gốc repo
cd grader && ./mvnw spring-boot:run        # Windows: .\mvnw.cmd spring-boot:run

# 3) Frontend (cửa sổ riêng)
cd frontend && npm install && npm run dev   # http://localhost:3000
```

Yêu cầu: Docker Desktop đang chạy, JDK 17+, Node 20+.

### Cơ sở dữ liệu (clone về chạy ngay)

Default backend khớp với MySQL trong `docker-compose.yml`, **không cần sửa gì**:
`localhost:3306` · DB `chamthi_db` · user `root` / pass `123456`.

- **Cách A (khuyến nghị):** `docker compose up -d` → MySQL tự tạo DB + bảng (qua `mysql/init.sql`).
- **Cách B (đã có MySQL native trên máy):** tạo sẵn `CREATE DATABASE chamthi_db;` rồi chạy backend
  (Hibernate `ddl-auto: update` tự tạo bảng). Nếu user/pass khác `root/123456`, đặt biến môi trường:
  `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATASOURCE_URL`.

### Cấu hình backend (biến môi trường)

| Biến | Mặc định | Ý nghĩa |
|------|----------|---------|
| `GRADER_TEMPLATE_DIR` | `grader-base` | Thư mục chứa Dockerfile.base (tương đối CWD) |
| `GRADER_EXAMS_DIR`    | `exams`      | Nơi lưu testcase (mount lúc chấm) |
| `GRADER_BASE_IMAGE`   | `grading-base:latest` | Ảnh nền dùng chung (ảnh duy nhất) |
| `GRADER_MAX_CONCURRENT` | `8` | Số bài chấm song song |
| `GRADER_TIMEOUT_SECONDS` | `240` | Timeout mỗi bài |
| `GRADER_SAVE_SUBMISSIONS` | `true` | Lưu zip bài nộp để audit |
| `GRADER_SUBMISSIONS_RETENTION_DAYS` | `30` | Số ngày giữ bài nộp (≤0 = mãi) |

> Chạy backend từ **thư mục gốc repo** để `grader-base`/`exams` (đường dẫn tương đối)
> trỏ đúng, hoặc đặt biến môi trường tuyệt đối.

## Luồng sử dụng

1. **Cấu hình đề** (`/teacher`): upload ZIP testcase chứa `exam_test.dart`,
   `grader.dart`, `skills_matrix.json` → hệ thống **lưu testcase** (mount lúc chấm, không build image).
   - 💡 Sinh nhanh bộ testcase bằng AI: xem [`docs/prompt-tao-testcase.md`](docs/prompt-tao-testcase.md)
     — dán prompt + đề bài vào Claude để tạo 3 file đúng định dạng.
2. **Chấm hàng loạt** (`/`): kéo thả các file `MaSV_HoTen.zip` (chứa `lib/`),
   theo dõi tiến độ realtime, xuất CSV.
3. **Thống kê** (`/statistics`): phổ điểm, tỉ lệ đạt/trượt.

## Định dạng file nộp của sinh viên

`MaSV_HoTen.zip` — bên trong có thư mục `lib/` chứa code `.dart`. Sinh viên chỉ
được dùng package đã cài sẵn trong ảnh nền (`pubspec.base.yaml`).

## API chính

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| POST   | `/api/exam-setup/upload-testcase` | Upload testcase (lưu để mount lúc chấm) |
| DELETE | `/api/exam-setup/{examId}` | Xóa đề: gỡ testcase + bản ghi DB (+ ảnh legacy nếu có) |
| POST   | `/api/batch/upload` | Upload hàng loạt bài SV để chấm |
| GET    | `/api/batch/progress/{batchId}` | Tiến độ + kết quả batch |
| GET    | `/api/results/{examId}/{studentId}` | JSON đầy đủ 1 bài (cho AI) |
| GET    | `/api/results/batch/{batchId}` | JSON tất cả bài trong batch |
| GET    | `/api/statistics/exams` | Danh sách đề thi (cho dropdown lọc) |
| GET    | `/api/statistics?examId=ALL` | Số liệu tổng hợp: phổ điểm, tỉ lệ đạt, tiến độ 7 ngày |

## Lưu trữ & Audit (tra lại khi chấm sai)

Mỗi bài chấm xong, hệ thống giữ lại 3 thứ để đối chiếu/chấm lại:

| Dữ liệu | Nơi lưu |
|---|---|
| **Bài nộp gốc** (zip SV) | `submissions/<đề>/<batch>/<MaSV>.zip` (tắt bằng `GRADER_SAVE_SUBMISSIONS=false`) |
| **Testcase đề** | `exams/<đề>/testcase/` (giữ vì cần mount lúc chấm) |
| **Kết quả chi tiết** | DB `exam_results.result_json` (test nào pass/fail, expected/actual, điểm) |

Bài nộp cũ hơn `GRADER_SUBMISSIONS_RETENTION_DAYS` (mặc định 30) tự dọn lúc 3h sáng.

## Quản lý dung lượng Docker

- **Chỉ có 1 image** `grading-base` (~4.5GB). Setup đề **không tạo image**, chấm bài **không tạo image**
  (container tạm `--rm` tự xóa) → Docker không tích tụ.
- Dọn rác an toàn (image `<none>` + build cache, không đụng volume/dự án khác):
  ```powershell
  ./grader-base/prune-grader.ps1     # Windows
  ./grader-base/prune-grader.sh      # Linux/macOS
  ```
- Đề cũ còn ảnh `grading-env-*` (legacy)? Xóa: `DELETE /api/exam-setup/{đề}` hoặc `docker rmi grading-env-<đề>`.
