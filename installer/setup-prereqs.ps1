<#
  setup-prereqs.ps1 - Cai cac THANH PHAN NEN cho may TRONG (chay 1 lan, idempotent).
  Inno Setup goi file nay khi cai (co quyen admin). Cung co the chay lai bang tay sau khi reboot.

  Lam gi:
    1) Dung winget cai (neu thieu): Docker Desktop, Node.js LTS, Temurin JDK 17.
    2) Neu Docker da san sang -> build anh nen cham bai 'grading-base'.

  Cach chay tay:
    powershell -ExecutionPolicy Bypass -File .\setup-prereqs.ps1
#>

param(
  [string]$AppDir        = (Split-Path $PSScriptRoot -Parent),  # thu muc cai = goc Grader_App
  [string]$DockerDataRoot = "",                                 # rong = dung mac dinh Docker (C:\ProgramData\docker)
  [switch]$SkipModels
)

$ErrorActionPreference = "Continue"
$script:CriticalSetupFailed = $false

function Have($c) { return [bool](Get-Command $c -ErrorAction SilentlyContinue) }
function Section($t) { Write-Host "`n==== $t ====" -ForegroundColor Cyan }
function Refresh-Path {
  $m = [Environment]::GetEnvironmentVariable("Path","Machine")
  $u = [Environment]::GetEnvironmentVariable("Path","User")
  $env:Path = (@($m,$u) | Where-Object { $_ }) -join ';'
}
function Get-Jdk17Home {
  $candidates = @()
  foreach ($scope in @("Process","User","Machine")) {
    $jh = [Environment]::GetEnvironmentVariable("JAVA_HOME", $scope)
    if ($jh) { $candidates += $jh }
  }

  $javac = Get-Command "javac" -ErrorAction SilentlyContinue
  if ($javac -and $javac.Source) {
    $candidates += (Split-Path (Split-Path $javac.Source -Parent) -Parent)
  }

  $roots = @(
    (Join-Path $env:ProgramFiles "Eclipse Adoptium"),
    (Join-Path $env:ProgramFiles "Java"),
    (Join-Path $env:ProgramFiles "Microsoft")
  )
  $pf86 = [Environment]::GetEnvironmentVariable("ProgramFiles(x86)")
  if ($pf86) { $roots += (Join-Path $pf86 "Eclipse Adoptium") }

  foreach ($root in $roots) {
    if (Test-Path $root) {
      Get-ChildItem -Path $root -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { $candidates += $_.FullName }
    }
  }

  foreach ($home in ($candidates | Where-Object { $_ } | Select-Object -Unique)) {
    try {
      $resolved = (Resolve-Path $home -ErrorAction Stop).Path
      $javacExe = Join-Path $resolved "bin\javac.exe"
      if (Test-Path $javacExe) {
        $ver = (& $javacExe -version 2>&1 | Out-String).Trim()
        if ($ver -match '^javac\s+17\.') { return $resolved }
      }
    } catch {}
  }
  return $null
}
function Set-JdkEnvironment([string]$jdkHome) {
  if (-not $jdkHome) { return }
  $jdkBin = Join-Path $jdkHome "bin"

  try { [Environment]::SetEnvironmentVariable("JAVA_HOME", $jdkHome, "Machine") } catch {}

  $machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
  $parts = @($machinePath -split ';') | Where-Object { $_ }
  $hasJdkBin = $false
  foreach ($p in $parts) {
    $norm = $p.Trim().TrimEnd('\')
    if ($norm -ieq "%JAVA_HOME%\bin" -or $norm -ieq $jdkBin.TrimEnd('\')) { $hasJdkBin = $true }
  }
  if (-not $hasJdkBin) {
    $newPath = if ($machinePath) { "%JAVA_HOME%\bin;$machinePath" } else { "%JAVA_HOME%\bin" }
    try { [Environment]::SetEnvironmentVariable("Path", $newPath, "Machine") } catch {}
  }

  $env:JAVA_HOME = $jdkHome
  if (($env:Path -split ';' | ForEach-Object { $_.Trim().TrimEnd('\') }) -notcontains $jdkBin.TrimEnd('\')) {
    $env:Path = "$jdkBin;$env:Path"
  }
}
function Ensure-Jdk17 {
  $jdkHome = Get-Jdk17Home
  if ($jdkHome) {
    Set-JdkEnvironment $jdkHome
    Write-Host "  [OK] Java JDK 17 da co: $jdkHome" -ForegroundColor Green
    return
  }

  Write-Host "  Cai Java JDK 17 (winget: EclipseAdoptium.Temurin.17.JDK) ..." -ForegroundColor Yellow
  winget install -e --id EclipseAdoptium.Temurin.17.JDK --silent --accept-package-agreements --accept-source-agreements --disable-interactivity 2>&1 | Out-Host
  Refresh-Path
  $jdkHome = Get-Jdk17Home
  if ($jdkHome) {
    Set-JdkEnvironment $jdkHome
    Write-Host "  [OK] Java JDK 17 da san sang: $jdkHome" -ForegroundColor Green
  } else {
    Write-Host "  [LOI] Khong tim thay javac sau khi cai JDK. Hay khoi dong lai may hoac cai Temurin JDK 17." -ForegroundColor Red
    $script:CriticalSetupFailed = $true
  }
}

# -- 1) Cai cong cu nen bang winget -------------------------------------------
Section "Cai thanh phan nen (winget)"
if (-not (Have "winget")) {
  Write-Host "[LOI] Khong co winget (App Installer). Cap nhat 'App Installer' tu Microsoft Store roi chay lai." -ForegroundColor Red
  $script:CriticalSetupFailed = $true
} else {
  function Ensure-Tool($check, $id, $name, [bool]$critical = $true) {
    if (Have $check) { Write-Host "  [OK] $name da co" -ForegroundColor Green; return }
    Write-Host "  Cai $name  (winget: $id) ..." -ForegroundColor Yellow
    winget install -e --id $id --silent --accept-package-agreements --accept-source-agreements --disable-interactivity 2>&1 | Out-Host
    Refresh-Path
    if (Have $check) {
      Write-Host "  [OK] $name da san sang" -ForegroundColor Green
    } elseif ($critical) {
      Write-Host "  [LOI] $name chua san sang sau khi cai. Hay khoi dong lai may roi chay lai grader-setup.cmd." -ForegroundColor Red
      $script:CriticalSetupFailed = $true
    } else {
      Write-Host "  [CANH BAO] $name chua san sang sau khi cai. Co the can khoi dong lai may." -ForegroundColor Yellow
    }
  }
  Ensure-Tool "node"   "OpenJS.NodeJS.LTS"               "Node.js LTS"
  Ensure-Jdk17
  Ensure-Tool "docker" "Docker.DockerDesktop"             "Docker Desktop"

  # -- Python: xu ly rieng vi Windows Store stub co the chan lenh 'python' ----
  Section "Cai dat Python 3.11"

  # Buoc 1: Tat Python Store stub (python.exe gia tu Microsoft Store)
  # Stub nay tra ve exit code 9009 va khong lam gi ca - la nguyen nhan chinh cua loi "python not found".
  $stubPath = "$env:LOCALAPPDATA\Microsoft\WindowsApps"
  $stubPy   = Join-Path $stubPath "python.exe"
  $stubPy3  = Join-Path $stubPath "python3.exe"
  foreach ($stub in @($stubPy, $stubPy3)) {
    if (Test-Path $stub) {
      try {
        # Doi ten stub de vo hieu hoa (khong xoa - tranh loi quyen)
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
    else        { Write-Host "  [CANH BAO] Python chua san sang - co the can KHOI DONG LAI may." -ForegroundColor Yellow }
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

# -- 1b) Cau hinh Docker data-root sang o khac (neu duoc chi dinh) -------------
if ($DockerDataRoot -and $DockerDataRoot -ne "") {
  Section "Cau hinh Docker data-root: $DockerDataRoot"
  $dockerRootOk = $true
  try {
    $driveRoot = [System.IO.Path]::GetPathRoot($DockerDataRoot)
    $driveLetter = $driveRoot.Substring(0, 1)
    $vol = Get-Volume -DriveLetter $driveLetter -ErrorAction SilentlyContinue
    $drive = Get-PSDrive -Name $driveLetter -ErrorAction SilentlyContinue
    if ($vol) {
      if ($vol.FileSystem -ne "NTFS") {
        Write-Host "  [CANH BAO] Docker data-root can o dia NTFS. O $driveLetter dang la $($vol.FileSystem) -> bo qua tuy chon nay." -ForegroundColor Yellow
        $dockerRootOk = $false
      }
      if ($vol.DriveType -and $vol.DriveType -ne "Fixed") {
        Write-Host "  [CANH BAO] Docker data-root khong nen dat tren USB/network drive -> bo qua tuy chon nay." -ForegroundColor Yellow
        $dockerRootOk = $false
      }
    }
    if ($drive -and $drive.Free -lt 30GB) {
      Write-Host "  [CANH BAO] O $driveLetter con it hon 30GB trong. Build Docker co the loi input/output." -ForegroundColor Yellow
    }
  } catch {
    Write-Host "  [CANH BAO] Khong kiem tra duoc o dia Docker data-root: $($_.Exception.Message)" -ForegroundColor Yellow
  }

  if (-not $dockerRootOk) {
    $DockerDataRoot = ""
  }
}

if ($DockerDataRoot -and $DockerDataRoot -ne "") {
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

# -- 2) Anh nen cham bai 'grading-base' (can Docker engine chay) ---------------
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
      try {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $base
        if ($LASTEXITCODE -ne 0) {
          Write-Host "  [CANH BAO] Chua build duoc grading-base. Xem huong dan loi phia tren, sau do chay lai grader-setup.cmd." -ForegroundColor Yellow
          $script:CriticalSetupFailed = $true
        }
      } catch {
        Write-Host "  [LOI] build-base: $_" -ForegroundColor Yellow
        $script:CriticalSetupFailed = $true
      }
      Pop-Location
    }
  } else {
    Write-Host "  [CHUA SAN SANG] Docker moi cai thuong can REBOOT + mo Docker Desktop 1 lan." -ForegroundColor Yellow
    Write-Host "  Sau khi reboot: chay lai file nay de build grading-base + tai model." -ForegroundColor Yellow
  }
}

Section "Hoan tat setup-prereqs"
Write-Host "  Neu vua cai Docker lan dau: KHOI DONG LAI MAY, mo Docker Desktop, roi chay lai file nay." -ForegroundColor DarkGray
if ($script:CriticalSetupFailed) {
  Write-Host "  [CHUA SAN SANG] Co buoc bat buoc chua thanh cong. Sua loi phia tren roi chay lai grader-setup.cmd." -ForegroundColor Red
  exit 1
}
Write-Host "  Xong roi -> dung shortcut 'Khoi dong Grader' (GraderLauncher.exe)." -ForegroundColor Green
