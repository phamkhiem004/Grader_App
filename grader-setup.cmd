@echo off
REM ============================================================================
REM  grader-setup.cmd - CHI cai cac THANH PHAN NEN (Docker / Node / Java /
REM  Python / Ollama + model AI + anh nen cham bai grading-base) NGAY TAI repo
REM  nay. KHONG tao ban sao repo. Cai xong thi chay GraderLauncher.exe de mo app.
REM
REM  Cach dung:
REM    - Double-click file nay, HOAC go  grader-setup  trong terminal tai repo.
REM    - Tu xin quyen admin (winget can quyen admin de cai Docker/Node/JDK/...).
REM    - Chay lai duoc nhieu lan (idempotent): thu gi da co thi bo qua.
REM ============================================================================
setlocal
cd /d "%~dp0"

REM 1) Bao dam quyen admin; neu chua thi mo lai chinh file nay voi UAC roi thoat.
net session >nul 2>&1
if errorlevel 1 (
  echo === Xin quyen admin de cai thanh phan nen... ===
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -Verb RunAs -FilePath '%~f0'"
  exit /b
)

REM 2) Cai prereqs NGAY TAI repo nay (-AppDir = thu muc chua file nay).
echo === Cai thanh phan nen (lan dau co the RAT lau: tai Docker/JDK/Node + model AI) ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0installer\setup-prereqs.ps1" -AppDir "%~dp0."

echo.
echo [OK] Xong buoc cai thanh phan nen.
echo   - Neu VUA cai Docker lan dau: khoi dong lai may, mo Docker Desktop 1 lan,
echo     roi chay lai  grader-setup  (de build anh grading-base + tai model con thieu).
echo   - Chay app: double-click  GraderLauncher.exe  (cung thu muc nay) hoac go  run
echo     trong terminal. Sau do mo  http://localhost:3000
echo.
pause
endlocal
