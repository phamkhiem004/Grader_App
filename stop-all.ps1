<#
  stop-all.ps1 — DỪNG app: bot(:8000) + backend(:8080) + frontend(:3000) và đóng các cửa sổ service.
  MySQL/Ollama (hạ tầng) vẫn để chạy cho lần mở sau nhanh. Mở lại app: .\run
  Dùng: .\stop   (hoặc .\pause)
#>
$ErrorActionPreference = "Continue"
$stopped = 0

# 1) Đóng các cửa sổ PowerShell đang chạy service (nhận diện theo dòng lệnh)
$markers = @('uvicorn app.main', 'spring-boot:run', 'npm run dev', 'ingest_rag', 'mvnw.cmd')
try {
  Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    $cl = $_.CommandLine
    if ($cl -and ($markers | Where-Object { $cl -like "*$_*" })) {
      try { Stop-Process -Id $_.ProcessId -Force; $stopped++ } catch {}
    }
  }
} catch {}

# 2) Kill tiến trình đang giữ cổng service (chắc chắn dừng hẳn).
#    Backend có thể TỰ ĐỔI cổng (8080 bận do Tomcat → 8081/8082...), nên quét cả dải 8080-8090.
$ports = @(8000, 3000) + (8080..8090)
foreach ($p in $ports) {
  Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
      $pid2 = $_
      $pname = (Get-Process -Id $pid2 -ErrorAction SilentlyContinue).ProcessName
      # Chỉ dừng tiến trình CỦA APP (java/python/node) — tránh giết Tomcat của bạn trên 8080.
      if ($pname -and $pname -notmatch '^(java|javaw|python|python3|node)$') {
        if ($p -eq 8080) { return }   # 8080 do Tomcat lạ giữ → bỏ qua
      }
      try { Stop-Process -Id $pid2 -Force; $stopped++; Write-Host "  Da dung service o cong $p ($pname, PID $pid2)" -ForegroundColor Yellow } catch {}
    }
}

Write-Host ""
Write-Host "App da dung ($stopped tien trinh). MySQL/Ollama van chay." -ForegroundColor Green
Write-Host "Mo lai app: .\run" -ForegroundColor DarkGray
