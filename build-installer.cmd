@echo off
REM build-installer.cmd — Tao bo cai Grader-Setup.exe (cho may trong).
REM Tu lo: tao GraderLauncher.exe -> cai Inno Setup (neu thieu) -> bien dich .iss.
setlocal
cd /d "%~dp0"

REM 1) Bao dam GraderLauncher.exe ton tai (bo cai can dong goi no)
if not exist "%~dp0GraderLauncher.exe" (
  echo === Tao GraderLauncher.exe truoc ===
  call "%~dp0build-exe.cmd"
)

REM 2) Tim ISCC.exe (trinh bien dich Inno Setup)
set "ISCC="
for %%p in ("C:\Program Files (x86)\Inno Setup 6\ISCC.exe" "C:\Program Files\Inno Setup 6\ISCC.exe" "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe") do if exist %%~p set "ISCC=%%~p"

if not defined ISCC (
  echo === Chua co Inno Setup -> cai bang winget ===
  winget install -e --id JRSoftware.InnoSetup --silent --accept-package-agreements --accept-source-agreements
  for %%p in ("C:\Program Files (x86)\Inno Setup 6\ISCC.exe" "C:\Program Files\Inno Setup 6\ISCC.exe" "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe") do if exist %%~p set "ISCC=%%~p"
)

if not defined ISCC (
  echo [LOI] Khong tim thay ISCC.exe. Cai Inno Setup tay tai https://jrsoftware.org/isdl.php roi chay lai.
  pause
  exit /b 1
)

echo === Bien dich bo cai ===
"%ISCC%" "%~dp0installer\grader-setup.iss"
if errorlevel 1 ( echo [LOI] Bien dich that bai. & pause & exit /b 1 )

echo.
echo [OK] Da tao bo cai: %~dp0installer\Output\Grader-Setup.exe
echo Gui file Grader-Setup.exe nay cho giao vien -> ho chi viec chay va lam theo huong dan.
pause
