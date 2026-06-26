# build-base.ps1 — Build ảnh nền dùng chung (chạy 1 lần, hoặc khi đổi pubspec.base.yaml)
# Dùng:  ./build-base.ps1
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:DOCKER_BUILDKIT = "1"

Write-Host "Building grading-base:latest (lan dau co the mat vai phut)..." -ForegroundColor Cyan
docker build -f "$here/Dockerfile.base" -t grading-base:latest $here
if ($LASTEXITCODE -ne 0) { Write-Error "Build that bai"; exit 1 }
Write-Host "OK - grading-base:latest da san sang. Cac de thi se build trong vai giay." -ForegroundColor Green
