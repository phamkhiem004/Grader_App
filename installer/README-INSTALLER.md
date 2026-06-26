# Bộ cài Grader (Inno Setup) — cho máy trống

Tạo một file **`Grader-Setup.exe`** để giáo viên cài như phần mềm bình thường. Bộ cài tự
chép app + (tùy chọn) cài Docker/Node/Java/Python/Ollama + tải model AI, rồi tạo shortcut.

## A. Bên bạn (người đóng gói) — build 1 lần

Tại `D:\FPT\Capstone`, chạy:

```
build-installer.cmd
```

Nó tự: tạo `GraderLauncher.exe` → cài Inno Setup nếu thiếu (winget) → biên dịch
`installer\grader-setup.iss` → xuất ra:

```
installer\Output\Grader-Setup.exe
```

Gửi **một file** `Grader-Setup.exe` đó cho giáo viên.

## B. Bên giáo viên — cài & chạy

1. Chạy `Grader-Setup.exe` (chuột phải → Run as administrator nếu cần).
   - Tích **"Cài các thành phần cần thiết…"** nếu máy **chưa có** Docker/Node/Java/Python/Ollama.
   - Bỏ tích nếu máy đã có sẵn.
2. Bộ cài chép app vào `C:\Grader`, rồi (nếu đã tích) chạy `setup-prereqs.ps1`:
   cài runtime bằng winget + tải model `qwen3:14b` + `bge-m3` + build ảnh chấm `grading-base`.
3. **Nếu vừa cài Docker lần đầu**: khởi động lại máy → mở Docker Desktop 1 lần →
   chạy lại shortcut **"Cài lại thành phần nền"** (để build `grading-base` + tải model còn thiếu).
4. Xong: bấm shortcut **"Khởi động Grader"** (hoặc icon ngoài Desktop) → mở
   `http://localhost:3000`.

## Yêu cầu để build / cài

- **Build (bên bạn)**: Windows + winget (có sẵn Win10/11). Inno Setup tự cài.
- **Cài (giáo viên)**: Windows 10 1809+ (cần winget để tự cài runtime). Quyền admin.
  Docker Desktop cần WSL2/ảo hoá bật trong BIOS — đây là giới hạn của Docker, không phải app.

## Ghi chú

- Bộ cài **không nhúng** Docker/Ollama/JDK… vào trong nó (sẽ vài GB). Nó **cài hộ** qua winget.
- Cài vào `C:\Grader` và cấp quyền ghi cho User để launcher tạo được `.venv`, `node_modules`,
  `submissions`… lúc chạy.
- Tải lại code mới: chạy `build-installer.cmd` lại để ra `Grader-Setup.exe` mới.
