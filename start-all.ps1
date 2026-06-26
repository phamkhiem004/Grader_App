<#
  start-all.ps1 — Chạy CẢ hệ thống chỉ bằng 1 lệnh (đặt ở GỐC repo Grader_App).
  Điều phối: Ollama (model) -> MySQL (docker) -> Feedback bot -> Backend -> Frontend.
  Mỗi service mở trong 1 cửa sổ PowerShell riêng (đóng cửa sổ = tắt service đó).

  Cách dùng (mở terminal tại thư mục Grader_App):
      powershell -ExecutionPolicy Bypass -File .\start-all.ps1
  Đổi model feedback: sửa 1 dòng trong file  bot-model.txt  (hoặc dùng -Model):
      powershell -ExecutionPolicy Bypass -File .\start-all.ps1 -Model qwen3:8b
#>

param(
  [string]$Model = "",            # model sinh feedback; rỗng = đọc từ bot-model.txt
  [string]$Embed = "bge-m3",      # model embedding cho RAG
  [switch]$SkipOllama,            # bỏ qua kiểm tra/pull model (nếu đã có sẵn)
  [switch]$SkipMysql              # bỏ qua docker compose (nếu MySQL đã chạy)
)

# KHÔNG dùng "Stop": 1 lỗi không nghiêm trọng (vd Start-Process không thấy ollama, docker chưa bật)
# sẽ làm DỪNG cả script → app không lên. Dùng "Continue"; các lỗi NGHIÊM TRỌNG vẫn chặn bằng `throw`.
$ErrorActionPreference = "Continue"
$root       = $PSScriptRoot          # = thư mục Grader_App (mọi thứ nằm trong đây)
$botDir     = Join-Path $root "feedback-bot"
$composeDir = $root                  # docker-compose.yml ở gốc Grader_App
$beDir      = Join-Path $root "grader"
$feDir      = Join-Path $root "frontend"

# Model bot: ưu tiên tham số -Model; nếu trống thì đọc 1 dòng trong bot-model.txt; cuối cùng mặc định.
if (-not $Model) {
  $modelFile = Join-Path $root "bot-model.txt"
  if (Test-Path $modelFile) {
    $Model = (Get-Content $modelFile | Where-Object { $_ -and -not $_.TrimStart().StartsWith('#') } | Select-Object -First 1)
    if ($Model) { $Model = $Model.Trim() }
  }
  if (-not $Model) { $Model = "qwen3:14b" }
}

# FAST-PATH: nếu bot-model.txt ghi "openai:gpt-4o-mini" → dùng API (NHANH + song song, hợp chấm hàng loạt).
# Key lấy lại từ grader/secret.properties (grader.ai.openai.api-key) — không phải khai 2 lần.
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
if ($useOpenAi) { Write-Host "Feedback provider: OPENAI ($openAiModel) — nhanh, chay song song duoc" -ForegroundColor Cyan }
else            { Write-Host "Feedback provider: OLLAMA ($Model) — local" -ForegroundColor Cyan }

function Have($cmd) { return [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }
function Section($t) { Write-Host "`n==== $t ====" -ForegroundColor Cyan }

# ── 0) Kiểm tra công cụ cần thiết ────────────────────────────────────────────
Section "Kiem tra cong cu"
foreach ($t in @("python","node","npm","docker")) {
  if (Have $t) { Write-Host "  [OK] $t" -ForegroundColor Green }
  else { Write-Host "  [THIEU] $t — cai dat truoc khi chay" -ForegroundColor Yellow }
}
if (-not (Test-Path $botDir)) { throw "Khong thay repo feedback bot tai: $botDir" }
if (-not (Test-Path $beDir))  { throw "Khong thay backend tai: $beDir" }

# ── 1) Ollama: bảo đảm đang chạy + đã có model ───────────────────────────────
if (-not $SkipOllama) {
  Section "Ollama (model AI feedback)"
  if (-not (Have "ollama")) {
    Write-Host "  [THIEU] ollama — tai tai https://ollama.com/download roi chay lai." -ForegroundColor Yellow
  } else {
    # Bảo đảm server Ollama đang chạy (cổng 11434). Dùng ĐƯỜNG DẪN ĐẦY ĐỦ + try/catch để
    # "Start-Process không thấy file" không làm chết script.
    try { Invoke-RestMethod "http://localhost:11434/api/tags" -TimeoutSec 3 | Out-Null }
    catch {
      Write-Host "  Khoi dong Ollama server..."
      try { Start-Process (Get-Command ollama).Source -ArgumentList "serve" -WindowStyle Minimized; Start-Sleep -Seconds 3 }
      catch { Write-Host "  [BO QUA] Khong tu bat duoc Ollama; hay mo Ollama thu cong roi chay lai." -ForegroundColor Yellow }
    }

    # openai: chỉ cần bge-m3 (RAG embeddings vẫn chạy LOCAL); KHÔNG pull model openai như ollama.
    $models = if ($useOpenAi) { @($Embed) } else { @($Model, $Embed) }
    $have = (& ollama list) 2>$null | Out-String
    foreach ($m in $models) {
      if ($have -match [regex]::Escape($m)) { Write-Host "  [OK] $m da co" -ForegroundColor Green }
      else { Write-Host "  Pull $m (lan dau co the lau)..." -ForegroundColor Yellow; & ollama pull $m }
    }
  }
}

# ── 2) Ghi .env cho bot (chốt model) ─────────────────────────────────────────
Section "Cau hinh feedback bot (.env)"
if ($useOpenAi) {
  $envContent = @"
FEEDBACK_PROVIDER=openai
OPENAI_MODEL=$openAiModel
OPENAI_API_KEY=$openAiKey
OPENAI_BASE_URL=https://api.openai.com/v1
EMBED_MODEL_NAME=$Embed
OLLAMA_TIMEOUT_SECONDS=600
"@
} else {
  $envContent = @"
FEEDBACK_PROVIDER=ollama
FEEDBACK_MODEL_NAME=$Model
EMBED_MODEL_NAME=$Embed
OLLAMA_TIMEOUT_SECONDS=600
"@
}
# Ghi .env KHÔNG BOM: PowerShell 'Set-Content -Encoding utf8' chèn BOM → python-dotenv đọc sai
# dòng đầu (FEEDBACK_PROVIDER) → fast-path openai bị bỏ qua. Dùng UTF8 không BOM.
[System.IO.File]::WriteAllText((Join-Path $botDir ".env"), $envContent, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "  Da ghi .env (provider=$(if($useOpenAi){'openai/'+$openAiModel}else{'ollama/'+$Model}))" -ForegroundColor Green

# ── 3) Docker: bao dam engine chay -> MySQL -> kiem tra anh nen cham bai ──────
if (-not $SkipMysql) {
  Section "Docker (MySQL + anh nen cham bai)"
  if (-not (Have "docker")) {
    Write-Host "  [THIEU] docker — cai Docker Desktop truoc (bo qua MySQL)." -ForegroundColor Yellow
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

# ── 4) Mở 3 cửa sổ: bot, backend, frontend ───────────────────────────────────
Section "Khoi dong cac service (moi service 1 cua so)"

# Tránh lỗi "Port already in use": CHỈ dọn instance CŨ CỦA CHÍNH APP (python/java/node) trên cổng,
# KHÔNG đụng tiến trình lạ (vd Tomcat của bạn trên 8080).
function Free-Port($port, $name, [string[]]$onlyProc) {
  Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
      $procId = $_
      if (-not $procId -or $procId -eq 0) { return }
      $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
      if (-not $p) { return }
      if ($onlyProc -and ($onlyProc -notcontains $p.ProcessName)) {
        Write-Host "  Cong $port dang bi '$($p.ProcessName)' (PID $procId) chiem — GIU NGUYEN (khong phai service cua app)." -ForegroundColor DarkYellow
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

# Backend: nếu 8080 vẫn bị chiếm (vd Tomcat) → tự chọn cổng trống kế tiếp; frontend trỏ theo.
function Test-PortFree($port) { -not (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) }
$bePort = 8080
if (-not (Test-PortFree 8080)) {
  $cand = 8081; while ($cand -le 8099 -and -not (Test-PortFree $cand)) { $cand++ }
  $bePort = $cand
  Write-Host "  Cong 8080 dang ban -> backend dung cong $bePort (khong dung tien trinh khac)." -ForegroundColor Yellow
}
# Ghi NEXT_PUBLIC_API_BASE vào frontend/.env.local (giữ các dòng khác) để FE gọi đúng cổng backend.
$apiBase  = "http://localhost:$bePort/api"
$envLocal = Join-Path $feDir ".env.local"
$keep = @(); if (Test-Path $envLocal) { $keep = Get-Content $envLocal | Where-Object { $_ -notmatch '^\s*NEXT_PUBLIC_API_BASE\s*=' } }
Set-Content -Path $envLocal -Value ($keep + "NEXT_PUBLIC_API_BASE=$apiBase") -Encoding utf8

# powershell.exe ĐẦY ĐỦ ĐƯỜNG DẪN (tránh 'không thấy file'); python launcher (tránh store-stub 'python')
$psExe = (Get-Command powershell -ErrorAction SilentlyContinue).Source
if (-not $psExe) { $psExe = "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" }
$venvPy = if (Get-Command py -ErrorAction SilentlyContinue) { "py -3" } else { "python" }

# Mở 1 cửa sổ service; try/catch để 1 cái lỗi KHÔNG chặn các cái còn lại.
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

# Backend Spring Boot (chay tren host). Windows DÙNG mvnw.cmd. Cổng = $bePort (8080 hoặc cổng trống).
$beCmd = @"
Set-Location '$beDir'
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
