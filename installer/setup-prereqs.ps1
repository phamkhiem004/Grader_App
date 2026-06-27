<#
  setup-prereqs.ps1 — Cai cac THANH PHAN NEN cho may TRONG (chay 1 lan, idempotent).
  Inno Setup goi file nay khi cai (co quyen admin). Cung co the chay lai bang tay sau khi reboot.

  Lam gi:
    1) Dung winget cai (neu thieu): Docker Desktop, Node.js LTS, Temurin JDK 17, Python 3, Ollama.
    2) Tai model Ollama: qwen3:14b (feedback) + bge-m3 (embedding RAG).
    3) Neu Docker da san sang -> build anh nen cham bai 'grading-base'.

  Cach chay tay:
    powershell -ExecutionPolicy Bypass -File .\setup-prereqs.ps1
#>

param(
  [string]$AppDir        = (Split-Path $PSScriptRoot -Parent),  # thu muc cai = goc Grader_App
  [string]$Model         = "",                                  # rong = doc tu bot-model.txt
  [string]$Embed         = "bge-m3",
  [string]$DockerDataRoot = "",                                 # rong = dung mac dinh Docker (C:\ProgramData\docker)
  [switch]$SkipModels
)

$ErrorActionPreference = "Continue"

# Model: uu tien -Model; rong thi doc bot-model.txt; cuoi cung mac dinh qwen3:14b
if (-not $Model) {
  $mf = Join-Path $AppDir "bot-model.txt"
  if (Test-Path $mf) {
    $Model = (Get-Content $mf | Where-Object { $_ -and -not $_.TrimStart().StartsWith('#') } | Select-Object -First 1)
    if ($Model) { $Model = $Model.Trim() }
  }
  if (-not $Model) { $Model = "qwen3:14b" }
}
function Have($c) { return [bool](Get-Command $c -ErrorAction SilentlyContinue) }
function Section($t) { Write-Host "`n==== $t ====" -ForegroundColor Cyan }
function Refresh-Path {
  $m = [Environment]::GetEnvironmentVariable("Path","Machine")
  $u = [Environment]::GetEnvironmentVariable("Path","User")
  $env:Path = (@($m,$u) | Where-Object { $_ }) -join ';'
}

# ── 1) Cai cong cu nen bang winget ───────────────────────────────────────────
Section "Cai thanh phan nen (winget)"
if (-not (Have "winget")) {
  Write-Host "[LOI] Khong co winget (App Installer). Cap nhat 'App Installer' tu Microsoft Store roi chay lai." -ForegroundColor Red
} else {
  function Ensure-Tool($check, $id, $name) {
    if (Have $check) { Write-Host "  [OK] $name da co" -ForegroundColor Green; return }
    Write-Host "  Cai $name  (winget: $id) ..." -ForegroundColor Yellow
    winget install -e --id $id --silent --accept-package-agreements --accept-source-agreements --disable-interactivity 2>&1 | Out-Host
    Refresh-Path
  }
  Ensure-Tool "node"   "OpenJS.NodeJS.LTS"               "Node.js LTS"
  Ensure-Tool "java"   "EclipseAdoptium.Temurin.17.JDK"  "Java (Temurin JDK 17)"
  Ensure-Tool "ollama" "Ollama.Ollama"                   "Ollama"
  Ensure-Tool "docker" "Docker.DockerDesktop"             "Docker Desktop"

  # ── Python: xu ly rieng vi Windows Store stub co the chan lenh 'python' ────
  Section "Cai dat Python 3.11"

  # Buoc 1: Tat Python Store stub (python.exe gia tu Microsoft Store)
  # Stub nay tra ve exit code 9009 va khong lam gi ca — la nguyen nhan chinh cua loi "python not found".
  $stubPath = "$env:LOCALAPPDATA\Microsoft\WindowsApps"
  $stubPy   = Join-Path $stubPath "python.exe"
  $stubPy3  = Join-Path $stubPath "python3.exe"
  foreach ($stub in @($stubPy, $stubPy3)) {
    if (Test-Path $stub) {
      try {
        # Doi ten stub de vo hieu hoa (khong xoa — tranh loi quyen)
        $bak = $stub + ".disabled_by_grader"
        if (-not (Test-Path $bak)) { Rename-Item $stub $bak -ErrorAction SilentlyContinue }
        Write-Host "  [OK] Da tat Python Store stub: $stub" -ForegroundColor Green
      } catch {
        Write-Host "  [INFO] Khong tat duoc stub (co the da tat san): $stub" -ForegroundColor DarkGray
      }
    }
  }

  # Buoc 2: Cai Python 3.11 neu chua co (hoac stub vua bi tat)
  Refresh-Path
  $pyOk = $false
  try {
    $ver = & python --version 2>&1
    if ($LASTEXITCODE -eq 0 -and $ver -match 'Python 3') { $pyOk = $true }
  } catch {}
  # Thu them launcher 'py'
  if (-not $pyOk) {
    try {
      $ver = & py -3 --version 2>&1
      if ($LASTEXITCODE -eq 0) { $pyOk = $true }
    } catch {}
  }

  if ($pyOk) {
    Write-Host "  [OK] Python da co va hoat dong ($ver)" -ForegroundColor Green
  } else {
    Write-Host "  Cai Python 3.11 (winget: Python.Python.3.11) ..." -ForegroundColor Yellow
    winget install -e --id Python.Python.3.11 --silent `
      --accept-package-agreements --accept-source-agreements `
      --override "/quiet InstallAllUsers=1 PrependPath=1 Include_pip=1" `
      2>&1 | Out-Host
    Refresh-Path
    # Kiem tra lai
    try { $ver = & python --version 2>&1; if ($LASTEXITCODE -eq 0) { $pyOk = $true } } catch {}
    if (-not $pyOk) { try { $ver = & py -3 --version 2>&1; if ($LASTEXITCODE -eq 0) { $pyOk = $true } } catch {} }
    if ($pyOk) { Write-Host "  [OK] Python da cai thanh cong: $ver" -ForegroundColor Green }
    else        { Write-Host "  [CANH BAO] Python chua san sang — co the can KHOI DONG LAI may." -ForegroundColor Yellow }
  }

  # Buoc 3: Nang cap pip va dam bao virtualenv co san (bot can tao .venv)
  if ($pyOk) {
    $pyExe = if (Get-Command py -ErrorAction SilentlyContinue) { "py" } else { "python" }
    Write-Host "  Nang cap pip..." -ForegroundColor DarkGray
    & $pyExe -m pip install --upgrade pip --quiet 2>&1 | Out-Null
    Write-Host "  [OK] pip da san sang" -ForegroundColor Green
  }

  Refresh-Path
}

# ── 1b) Cau hinh Docker data-root sang o khac (neu duoc chi dinh) ────────────
if ($DockerDataRoot -and $DockerDataRoot -ne "") {
  Section "Cau hinh Docker data-root: $DockerDataRoot"
  $daemonDir  = "$env:ProgramData\Docker\config"
  $daemonFile = "$daemonDir\daemon.json"
  New-Item -ItemType Directory -Force -Path $daemonDir | Out-Null

  # Doc hoac tao moi daemon.json
  if (Test-Path $daemonFile) {
    try   { $cfg = Get-Content $daemonFile -Raw | ConvertFrom-Json }
    catch { $cfg = [PSCustomObject]@{} }
  } else  { $cfg = [PSCustomObject]@{} }

  # Gan data-root
  if ($cfg.PSObject.Properties['data-root']) {
    $cfg.'data-root' = $DockerDataRoot
  } else {
    $cfg | Add-Member -MemberType NoteProperty -Name 'data-root' -Value $DockerDataRoot
  }

  $cfg | ConvertTo-Json -Depth 5 | Set-Content $daemonFile -Encoding UTF8
  Write-Host "  [OK] daemon.json da cap nhat: data-root = $DockerDataRoot" -ForegroundColor Green
  Write-Host "  Tao thu muc neu chua co: $DockerDataRoot" -ForegroundColor Yellow
  New-Item -ItemType Directory -Force -Path $DockerDataRoot | Out-Null

  # Restart Docker Desktop neu dang chay de ap dung cai dat
  $ddProc = Get-Process -Name "Docker Desktop" -ErrorAction SilentlyContinue
  if ($ddProc) {
    Write-Host "  Restart Docker Desktop de ap dung data-root moi..." -ForegroundColor Yellow
    Stop-Process -Name "Docker Desktop" -Force -ErrorAction SilentlyContinue
    Start-Sleep 3
    $ddExe = Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
    if (Test-Path $ddExe) { Start-Process $ddExe | Out-Null; Start-Sleep 5 }
  } else {
    Write-Host "  Docker Desktop chua chay -> cai dat se co hieu luc lan khoi dong tiep theo." -ForegroundColor DarkGray
  }
}

# ── 2) Tai model Ollama ──────────────────────────────────────────────────────
if (-not $SkipModels -and (Have "ollama")) {
  Section "Tai model Ollama ($Model + $Embed)"
  try { Invoke-RestMethod "http://localhost:11434/api/tags" -TimeoutSec 3 | Out-Null }
  catch { Start-Process "ollama" -ArgumentList "serve" -WindowStyle Minimized; Start-Sleep 3 }
  $have = (& ollama list) 2>$null | Out-String
  foreach ($m in @($Model, $Embed)) {
    if ($have -match [regex]::Escape($m)) { Write-Host "  [OK] $m da co" -ForegroundColor Green }
    else { Write-Host "  Pull $m (lan dau co the lau, ~vai GB)..." -ForegroundColor Yellow; & ollama pull $m }
  }
} elseif (-not (Have "ollama")) {
  Write-Host "  [BO QUA] Ollama chua san sang -> tai model sau (chay lai file nay)." -ForegroundColor Yellow
}

# ── 3) Anh nen cham bai 'grading-base' (can Docker engine chay) ───────────────
Section "Anh nen cham bai (grading-base)"
if (-not (Have "docker")) {
  Write-Host "  [BO QUA] Chua co docker." -ForegroundColor Yellow
} else {
  $dockerOk = $false
  try { & docker info *> $null; if ($LASTEXITCODE -eq 0) { $dockerOk = $true } } catch {}
  if (-not $dockerOk) {
    $dd = Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
    if (-not (Test-Path $dd)) {
      # Thu them duong dan khac (cai vao o khac qua winget custom location)
      $dd = Join-Path $env:LOCALAPPDATA "Docker\Docker Desktop.exe"
    }
    if (Test-Path $dd) {
      Write-Host "  Mo Docker Desktop, cho engine..." -ForegroundColor Yellow
      Start-Process $dd | Out-Null
      for ($i = 0; $i -lt 40 -and -not $dockerOk; $i++) { Start-Sleep 3; try { & docker info *> $null; if ($LASTEXITCODE -eq 0) { $dockerOk = $true } } catch {} }
    }
  }
  if ($dockerOk) {
    $base = Join-Path $AppDir "grader-base\build-base.ps1"
    & docker image inspect grading-base:latest *> $null
    if ($LASTEXITCODE -eq 0) {
      Write-Host "  [OK] grading-base da co" -ForegroundColor Green
    } elseif (Test-Path $base) {
      Write-Host "  Build grading-base (Flutter SDK, lan dau RAT LAU ~10-20 phut)..." -ForegroundColor Yellow
      Push-Location (Split-Path $base)
      try { & powershell -NoProfile -ExecutionPolicy Bypass -File $base } catch { Write-Host "  [LOI] build-base: $_" -ForegroundColor Yellow }
      Pop-Location
    }
  } else {
    Write-Host "  [CHUA SAN SANG] Docker moi cai thuong can REBOOT + mo Docker Desktop 1 lan." -ForegroundColor Yellow
    Write-Host "  Sau khi reboot: chay lai file nay de build grading-base + tai model." -ForegroundColor Yellow
  }
}

Section "Hoan tat setup-prereqs"
Write-Host "  Neu vua cai Docker lan dau: KHOI DONG LAI MAY, mo Docker Desktop, roi chay lai file nay." -ForegroundColor DarkGray
Write-Host "  Xong roi -> dung shortcut 'Khoi dong Grader' (GraderLauncher.exe)." -ForegroundColor Green
