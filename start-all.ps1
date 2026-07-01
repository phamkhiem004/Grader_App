<#
  start-all.ps1 - Chay CA he thong chi bang 1 lenh (dat o GOC repo Grader_App).
  Dieu phoi: Ollama (model) -> MySQL (docker) -> Feedback bot -> Backend -> Frontend.
  Moi service mo trong 1 cua so PowerShell rieng (dong cua so = tat service do).

  Cach dung (mo terminal tai thu muc Grader_App):
      powershell -ExecutionPolicy Bypass -File .\start-all.ps1
  Doi model feedback: sua 1 dong trong file bot-model.txt (hoac dung -Model):
      powershell -ExecutionPolicy Bypass -File .\start-all.ps1 -Model qwen3:8b
#>

param(
  [string]$Model = "",            # model sinh feedback; rong = doc tu bot-model.txt
  [string]$Embed = "bge-m3",      # model embedding cho RAG
  [switch]$SkipOllama,            # bo qua kiem tra/pull model (neu da co san)
  [switch]$SkipMysql              # bo qua docker compose (neu MySQL da chay)
)

# KHONG dung "Stop": 1 loi khong nghiem trong (vd Start-Process khong thay ollama, docker chua bat)
# se lam DUNG ca script -> app khong len. Dung "Continue"; cac loi NGHIEM TRONG van chan bang `throw`.
$ErrorActionPreference = "Continue"
$root       = $PSScriptRoot          # = thu muc Grader_App (moi thu nam trong day)
$botDir     = Join-Path $root "feedback-bot"
$composeDir = $root                  # docker-compose.yml o goc Grader_App
$beDir      = Join-Path $root "grader"
$feDir      = Join-Path $root "frontend"

# Model bot: uu tien tham so -Model; neu trong thi doc 1 dong trong bot-model.txt; cuoi cung mac dinh.
if (-not $Model) {
  $modelFile = Join-Path $root "bot-model.txt"
  if (Test-Path $modelFile) {
    $Model = (Get-Content $modelFile | Where-Object { $_ -and -not $_.TrimStart().StartsWith('#') } | Select-Object -First 1)
    if ($Model) { $Model = $Model.Trim() }
  }
  if (-not $Model) { $Model = "qwen3:14b" }
}

# FAST-PATH: neu bot-model.txt ghi "openai:gpt-4o-mini" -> dung API (NHANH + song song, hop cham hang loat).
# Key lay lai tu grader/secret.properties (grader.ai.openai.api-key) - khong phai khai 2 lan.
$useOpenAi = $false; $openAiModel = ""; $openAiKey = ""
if ($Model -match '^(?i)\s*openai\s*:\s*(.+)$') {
  $useOpenAi = $true
  $openAiModel = $Matches[1].Trim()
  $secret = Join-Path $beDir "secret.properties"
  if (Test-Path $secret) {
    $kl = Get-Content $secret | Where-Object { $_ -match '^\s*grader\.ai\.openai\.api-key\s*=' } | Select-Object -First 1
    if ($kl) { $openAiKey = ($kl -replace '^\s*grader\.ai\.openai\.api-key\s*=\s*', '').Trim() }
  }
  if (-not $openAiKey) {
    Write-Host "  [CANH BAO] bot-model.txt chon 'openai' nhung KHONG thay key o grader/secret.properties -> bot se loi." -ForegroundColor Yellow
  }
}
if ($useOpenAi) { Write-Host "Feedback provider: OPENAI ($openAiModel) - nhanh, chay song song duoc" -ForegroundColor Cyan }
else            { Write-Host "Feedback provider: OLLAMA ($Model) - local" -ForegroundColor Cyan }

function Have($cmd) { return [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }
function Section($t) { Write-Host "`n==== $t ====" -ForegroundColor Cyan }
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
function Use-Jdk17([string]$jdkHome) {
  if (-not $jdkHome) { return }
  $env:JAVA_HOME = $jdkHome
  $jdkBin = Join-Path $jdkHome "bin"
  if (($env:Path -split ';' | ForEach-Object { $_.Trim().TrimEnd('\') }) -notcontains $jdkBin.TrimEnd('\')) {
    $env:Path = "$jdkBin;$env:Path"
  }
}

# -- 0) Kiem tra cong cu can thiet --------------------------------------------
Section "Kiem tra cong cu"
foreach ($t in @("python","node","npm","docker")) {
  if (Have $t) { Write-Host "  [OK] $t" -ForegroundColor Green }
  else { Write-Host "  [THIEU] $t - cai dat truoc khi chay" -ForegroundColor Yellow }
}
$backendJdkHome = Get-Jdk17Home
if ($backendJdkHome) {
  Use-Jdk17 $backendJdkHome
  Write-Host "  [OK] JDK 17: $backendJdkHome" -ForegroundColor Green
} else {
  Write-Host "  [THIEU] JDK 17/javac - chay grader-setup.cmd de backend bien dich duoc." -ForegroundColor Yellow
}
if (-not (Test-Path $botDir)) { throw "Khong thay repo feedback bot tai: $botDir" }
if (-not (Test-Path $beDir))  { throw "Khong thay backend tai: $beDir" }

# -- 1) Ollama: bao dam dang chay + da co model -------------------------------
if (-not $SkipOllama) {
  Section "Ollama (model AI feedback)"
  if (-not (Have "ollama")) {
    Write-Host "  [THIEU] ollama - tai tai https://ollama.com/download roi chay lai." -ForegroundColor Yellow
  } else {
    # Bao dam server Ollama dang chay (cong 11434). Dung duong dan day du + try/catch de
    # "Start-Process khong thay file" khong lam chet script.
    try { Invoke-RestMethod "http://localhost:11434/api/tags" -TimeoutSec 3 | Out-Null }
    catch {
      Write-Host "  Khoi dong Ollama server..."
      try { Start-Process (Get-Command ollama).Source -ArgumentList "serve" -WindowStyle Minimized; Start-Sleep -Seconds 3 }
      catch { Write-Host "  [BO QUA] Khong tu bat duoc Ollama; hay mo Ollama thu cong roi chay lai." -ForegroundColor Yellow }
    }

    # openai: chi can bge-m3 (RAG embeddings van chay LOCAL); KHONG pull model openai nhu ollama.
    $models = if ($useOpenAi) { @($Embed) } else { @($Model, $Embed) }
    $have = (& ollama list) 2>$null | Out-String
    foreach ($m in $models) {
      if ($have -match [regex]::Escape($m)) { Write-Host "  [OK] $m da co" -ForegroundColor Green }
      else { Write-Host "  Pull $m (lan dau co the lau)..." -ForegroundColor Yellow; & ollama pull $m }
    }
  }
}

# -- 2) Ghi .env cho bot (chot model) -----------------------------------------
Section "Cau hinh feedback bot (.env)"
if ($useOpenAi) {
  $envContent = @"
FEEDBACK_PROVIDER=openai
OPENAI_MODEL=$openAiModel
OPENAI_API_KEY=$openAiKey
OPENAI_BASE_URL=https://api.openai.com/v1
EMBED_MODEL_NAME=$Embed
OLLAMA_TIMEOUT_SECONDS=600
OLLAMA_NUM_PREDICT=650
OPENAI_FEEDBACK_MAX_TOKENS=800
FEEDBACK_FAST_PERFECT=true
FEEDBACK_FAST_INVALID=true
FEEDBACK_MAX_RAG_CHARS_PER_ITEM=900
FEEDBACK_MAX_EVIDENCE_ITEMS=8
FEEDBACK_RAG_K_SMALL=3
FEEDBACK_RAG_K_MEDIUM=4
FEEDBACK_RAG_K_LARGE=5
"@
} else {
  $envContent = @"
FEEDBACK_PROVIDER=ollama
FEEDBACK_MODEL_NAME=$Model
EMBED_MODEL_NAME=$Embed
OLLAMA_TIMEOUT_SECONDS=600
OLLAMA_NUM_PREDICT=650
OPENAI_FEEDBACK_MAX_TOKENS=800
FEEDBACK_FAST_PERFECT=true
FEEDBACK_FAST_INVALID=true
FEEDBACK_MAX_RAG_CHARS_PER_ITEM=900
FEEDBACK_MAX_EVIDENCE_ITEMS=8
FEEDBACK_RAG_K_SMALL=3
FEEDBACK_RAG_K_MEDIUM=4
FEEDBACK_RAG_K_LARGE=5
"@
}
# Ghi .env KHONG BOM: PowerShell 'Set-Content -Encoding utf8' chen BOM -> python-dotenv doc sai
# dong dau (FEEDBACK_PROVIDER) -> fast-path openai bi bo qua. Dung UTF8 khong BOM.
[System.IO.File]::WriteAllText((Join-Path $botDir ".env"), $envContent, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "  Da ghi .env (provider=$(if($useOpenAi){'openai/'+$openAiModel}else{'ollama/'+$Model}))" -ForegroundColor Green

# -- 3) Docker: bao dam engine chay -> MySQL -> kiem tra anh nen cham bai ------
if (-not $SkipMysql) {
  Section "Docker (MySQL + anh nen cham bai)"
  if (-not (Have "docker")) {
    Write-Host "  [THIEU] docker - cai Docker Desktop truoc (bo qua MySQL)." -ForegroundColor Yellow
  } else {
    # Bao dam Docker engine dang chay (may moi cai Docker can mo 1 lan, doi khi phai reboot)
    $dockerOk = $false
    try { & docker info *> $null; if ($LASTEXITCODE -eq 0) { $dockerOk = $true } } catch {}
    if (-not $dockerOk) {
      $dd = Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
      if (Test-Path $dd) {
        Write-Host "  Khoi dong Docker Desktop, cho engine san sang..." -ForegroundColor Yellow
        Start-Process $dd | Out-Null
        for ($i = 0; $i -lt 60 -and -not $dockerOk; $i++) {
          Start-Sleep -Seconds 3
          try { & docker info *> $null; if ($LASTEXITCODE -eq 0) { $dockerOk = $true } } catch {}
        }
      }
    }
    if ($dockerOk) {
      Push-Location $composeDir
      try { & docker compose up -d; Write-Host "  MySQL dang chay (cong 3306)" -ForegroundColor Green }
      catch { Write-Host "  [LOI] docker compose that bai" -ForegroundColor Yellow }
      Pop-Location
      # Anh nen 'grading-base' (Flutter SDK) la BAT BUOC de CHAM BAI. Build 1 lan bang setup-prereqs.ps1.
      $hasBase = $false
      try { & docker image inspect grading-base:latest *> $null; if ($LASTEXITCODE -eq 0) { $hasBase = $true } } catch {}
      if ($hasBase) { Write-Host "  [OK] anh nen grading-base da co" -ForegroundColor Green }
      else { Write-Host "  [CANH BAO] Chua co anh nen grading-base -> CHUA cham bai duoc. Chay: grader-base\build-base.ps1" -ForegroundColor Yellow }
    } else {
      Write-Host "  [CANH BAO] Docker chua san sang. Mo Docker Desktop roi chay lai (cham bai can Docker)." -ForegroundColor Yellow
    }
  }
}

# -- 4) Mo 3 cua so: bot, backend, frontend -----------------------------------
Section "Khoi dong cac service (moi service 1 cua so)"

# Tranh loi "Port already in use": CHI don instance CU CUA CHINH APP (python/java/node) tren cong,
# KHONG dung tien trinh la (vd Tomcat cua ban tren 8080).
function Free-Port($port, $name, [string[]]$onlyProc) {
  Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
      $procId = $_
      if (-not $procId -or $procId -eq 0) { return }
      $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
      if (-not $p) { return }
      if ($onlyProc -and ($onlyProc -notcontains $p.ProcessName)) {
        Write-Host "  Cong $port dang bi '$($p.ProcessName)' (PID $procId) chiem - GIU NGUYEN (khong phai service cua app)." -ForegroundColor DarkYellow
        return
      }
      try {
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        Write-Host "  Giai phong cong $port ($name): da dung '$($p.ProcessName)' (PID $procId)" -ForegroundColor Yellow
      } catch {}
    }
}
Free-Port 8000 "bot"      @('python','python3')
Free-Port 8080 "backend"  @('java','javaw')
Free-Port 3000 "frontend" @('node')

# Backend: neu 8080 van bi chiem (vd Tomcat) -> tu chon cong trong ke tiep; frontend tro theo.
function Test-PortFree($port) { -not (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) }
$bePort = 8080
if (-not (Test-PortFree 8080)) {
  $cand = 8081; while ($cand -le 8099 -and -not (Test-PortFree $cand)) { $cand++ }
  $bePort = $cand
  Write-Host "  Cong 8080 dang ban -> backend dung cong $bePort (khong dung tien trinh khac)." -ForegroundColor Yellow
}
# Ghi NEXT_PUBLIC_API_BASE vao frontend/.env.local (giu cac dong khac) de FE goi dung cong backend.
$apiBase  = "http://localhost:$bePort/api"
$envLocal = Join-Path $feDir ".env.local"
$keep = @(); if (Test-Path $envLocal) { $keep = Get-Content $envLocal | Where-Object { $_ -notmatch '^\s*NEXT_PUBLIC_API_BASE\s*=' } }
# Ghi KHONG BOM: 'Set-Content -Encoding utf8' tren PS 5.1 chen BOM -> Next.js doc sai bien
# dau dong (NEXT_PUBLIC_API_BASE) -> FE goi sai cong backend. Dung UTF8 khong BOM (giong .env o tren).
$lines = @($keep) + "NEXT_PUBLIC_API_BASE=$apiBase"
[System.IO.File]::WriteAllText($envLocal, (($lines -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))

# powershell.exe day du duong dan (tranh 'khong thay file'); python launcher (tranh store-stub 'python')
$psExe = (Get-Command powershell -ErrorAction SilentlyContinue).Source
if (-not $psExe) { $psExe = "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" }
$venvPy = if (Get-Command py -ErrorAction SilentlyContinue) { "py -3" } else { "python" }

# Mo 1 cua so service; try/catch de 1 cai loi KHONG chan cac cai con lai.
function Launch($title, $cmd) {
  try {
    Start-Process $psExe -ArgumentList "-NoExit","-NoProfile","-ExecutionPolicy","Bypass","-Command",$cmd | Out-Null
    Write-Host "  [OK] Da mo cua so: $title" -ForegroundColor Green
  } catch {
    Write-Host "  [LOI] Khong mo duoc cua so $title : $($_.Exception.Message)" -ForegroundColor Yellow
  }
}

# Feedback bot: tao venv + cai deps (lan dau) + build RAG (lan dau) + uvicorn :8000
$botCmd = @"
Set-Location '$botDir'
if (-not (Test-Path '.venv')) {
  Write-Host 'Tao virtualenv + cai dependencies (lan dau, hoi lau)...' -ForegroundColor Yellow
  $venvPy -m venv .venv
  .\.venv\Scripts\python.exe -m pip install --upgrade pip
  .\.venv\Scripts\python.exe -m pip install -r requirements.txt
}
if (-not (Test-Path 'data\chroma_db')) {
  Write-Host 'Build RAG index (lan dau)...' -ForegroundColor Yellow
  .\.venv\Scripts\python.exe scripts\ingest_rag.py
}
Write-Host 'Feedback bot: http://localhost:8000' -ForegroundColor Green
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
"@
Launch "Feedback bot (:8000)" $botCmd

# Backend Spring Boot (chay tren host). Windows DUNG mvnw.cmd. Cong = $bePort (8080 hoac cong trong).
$beCmd = @"
Set-Location '$beDir'
if ('$backendJdkHome') {
  `$env:JAVA_HOME = '$backendJdkHome'
  `$env:Path = (Join-Path `$env:JAVA_HOME 'bin') + ';' + `$env:Path
  Write-Host "JDK: `$env:JAVA_HOME" -ForegroundColor DarkGray
} else {
  Write-Host '[LOI] Khong thay JDK 17 (javac). Hay chay grader-setup.cmd roi mo lai GraderLauncher.exe.' -ForegroundColor Red
  return
}
`$env:SERVER_PORT = '$bePort'
Write-Host 'Backend: http://localhost:$bePort' -ForegroundColor Green
.\mvnw.cmd spring-boot:run
"@
Launch "Backend (:$bePort)" $beCmd

# Frontend Next.js: :3000
$feCmd = @"
Set-Location '$feDir'
if (-not (Test-Path 'node_modules')) { Write-Host 'npm install (lan dau)...' -ForegroundColor Yellow; npm install }
Write-Host 'Frontend: http://localhost:3000' -ForegroundColor Green
npm run dev
"@
Launch "Frontend (:3000)" $feCmd

Section "Xong"
Write-Host "  Frontend : http://localhost:3000"
Write-Host "  Backend  : http://localhost:$bePort"
Write-Host "  Bot AI   : http://localhost:8000   (trang Nhan xet AI se bao 'Da ket noi')"
Write-Host "  Tat: dong tung cua so service (hoac Ctrl+C trong cua so do)." -ForegroundColor DarkGray
