# build-base.ps1 - Build anh nen dung chung (chay 1 lan, hoac khi doi pubspec.base.yaml)
# Dung: .\build-base.ps1
#
# Ghi chu:
# - Loi Docker "failed to compute cache key: commit failed: input/output error"
#   thuong do BuildKit cache/WSL disk/Docker data-root bi loi hoac het dung luong.
# - Script nay tu retry bang cache sach va fallback legacy builder de giam loi tren may moi cai.

[CmdletBinding()]
param(
  [switch]$NoCache
)

$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$dockerfile = Join-Path $here "Dockerfile.base"
$image = "grading-base:latest"

function Show-FreeSpace($path) {
  try {
    $resolved = Resolve-Path $path
    $root = [System.IO.Path]::GetPathRoot($resolved.Path)
    if ($root -and $root.Length -ge 2) {
      $driveName = $root.Substring(0, 1)
      $drive = Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue
      if ($drive) {
        $freeGb = [math]::Round($drive.Free / 1GB, 1)
        Write-Host "  Free space on ${driveName}: $freeGb GB" -ForegroundColor DarkGray
      }
    }
  } catch {}
}

function Ensure-DockerReady {
  & docker version *> $null
  if ($LASTEXITCODE -ne 0) {
    Write-Host "  [LOI] Docker chua san sang. Hay mo Docker Desktop, doi engine Ready, roi chay lai." -ForegroundColor Red
    exit 1
  }
}

function Invoke-Build($title, [string]$buildKit, [bool]$useNoCache, [bool]$plainProgress) {
  Write-Host ""
  Write-Host "== $title ==" -ForegroundColor Cyan
  $env:DOCKER_BUILDKIT = $buildKit

  $args = @("build")
  if ($plainProgress) { $args += @("--progress=plain") }
  if ($useNoCache) { $args += @("--no-cache") }
  $args += @("-f", $dockerfile, "-t", $image, $here)

  & docker @args
  $code = $LASTEXITCODE
  if ($code -eq 0) {
    Write-Host "  [OK] $title" -ForegroundColor Green
    return $true
  }

  Write-Host "  [LOI] $title that bai (exit=$code)" -ForegroundColor Yellow
  return $false
}

Ensure-DockerReady

Write-Host "Building grading-base:latest (lan dau co the mat 10-20 phut)..." -ForegroundColor Cyan
Show-FreeSpace $here

$ok = Invoke-Build "BuildKit build" "1" ([bool]$NoCache) $true

if (-not $ok) {
  Write-Host ""
  Write-Host "Docker build loi. Dang don BuildKit cache roi thu lai --no-cache..." -ForegroundColor Yellow
  try { & docker builder prune -af | Out-Host } catch {}
  $ok = Invoke-Build "BuildKit build --no-cache" "1" $true $true
}

if (-not $ok) {
  Write-Host ""
  Write-Host "BuildKit van loi. Thu legacy builder --no-cache (hay sua loi cache key/I/O tren Docker Desktop)..." -ForegroundColor Yellow
  $ok = Invoke-Build "Legacy docker build --no-cache" "0" $true $false
}

if (-not $ok) {
  Write-Host ""
  Write-Host "[KHONG BUILD DUOC grading-base]" -ForegroundColor Red
  Write-Host "Cach xu ly tren may bi loi input/output/cache:" -ForegroundColor Yellow
  Write-Host "  1) Mo Docker Desktop -> Troubleshoot -> Restart Docker Desktop, roi chay lai grader-setup.cmd."
  Write-Host "  2) Dam bao o dia Docker data la o cung noi bo, dinh dang NTFS, con toi thieu 30-40GB trong."
  Write-Host "  3) Khong dat Docker data tren USB/network drive/thu muc sync cloud."
  Write-Host "  4) Chay PowerShell Admin: wsl --shutdown, mo Docker Desktop lai, roi chay:"
  Write-Host "       cd grader-base"
  Write-Host "       .\build-base.ps1 -NoCache"
  Write-Host "  5) Neu van loi: Docker Desktop -> Troubleshoot -> Clean / Purge data, sau do chay lai grader-setup.cmd."
  exit 1
}

Write-Host ""
Write-Host "OK - grading-base:latest da san sang. Cac de thi se build trong vai giay." -ForegroundColor Green
