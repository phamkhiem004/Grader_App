# Cài Grader trên máy TRỐNG — clone repo + `grader-setup.cmd`

Luồng: **clone repo → chạy `grader-setup.cmd` (cài thành phần nền) → chạy `GraderLauncher.exe`**.
Mọi thứ chạy **tại chỗ trong repo**, không nhân bản, không cần đóng gói installer riêng.

## A. Cài thành phần nền (1 lần)

Tại thư mục repo (vd `D:\FPT\Capstone\Grader_App`), chạy:

```
grader-setup.cmd
```

Nó tự **xin quyền admin (UAC)** rồi gọi `installer\setup-prereqs.ps1`:

- Dùng **winget** cài (nếu thiếu): Docker Desktop, Node.js LTS, Temurin JDK 17, Python 3, Ollama.
- Tải model Ollama: model ghi trong `bot-model.txt` (mặc định `qwen3:14b`) + `bge-m3` (embedding RAG).
- Nếu Docker đã sẵn sàng → build ảnh nền chấm bài `grading-base` (Flutter SDK, lần đầu rất lâu).

> **Vừa cài Docker lần đầu?** Docker Desktop thường cần **khởi động lại máy** + mở Docker Desktop 1 lần.
> Sau khi reboot, chạy lại `grader-setup.cmd` để build `grading-base` + tải nốt model còn thiếu.

## B. Chạy app

Sau khi A xong, ngay trong repo:

- Double-click **`GraderLauncher.exe`**, hoặc gõ **`run`** trong terminal.
- Nó bật MySQL (Docker) + bot AI (`:8000`) + backend (`:8080`) + frontend (`:3000`).
- Mở **http://localhost:3000**.

## Yêu cầu

- Windows 10 1809+ (cần **winget** / App Installer để tự cài runtime). Quyền admin.
- Docker Desktop cần WSL2 / ảo hoá bật trong BIOS — giới hạn của Docker, không phải app.

## Ghi chú

- `grader-setup.cmd` **không nhúng** Docker/Ollama/JDK… (sẽ vài GB) — nó **cài hộ** qua winget.
- Chạy được **nhiều lần** (idempotent): thứ gì đã có thì bỏ qua.
- `.venv`, `node_modules`, `submissions/`, `exams/` được sinh ra **trong repo** lúc chạy (đã `.gitignore`).
