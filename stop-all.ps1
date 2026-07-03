<#
  stop-all.ps1 - DUNG app: backend(:8080) + frontend(:3000) va dong cac cua so service.
  MySQL (ha tang) van de chay cho lan mo sau nhanh. Mo lai app: .\run
  Dung: .\stop   (hoac .\pause)
#>
$ErrorActionPreference = "Continue"
$stopped = 0

# 1) Dong cac cua so PowerShell dang chay service (nhan dien theo dong lenh)
$markers = @('spring-boot:run', 'npm run dev', 'mvnw.cmd')
try {
  Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    $cl = $_.CommandLine
    if ($cl -and ($markers | Where-Object { $cl -like "*$_*" })) {
      try { Stop-Process -Id $_.ProcessId -Force; $stopped++ } catch {}
    }
  }
} catch {}

# 2) Kill tien trinh dang giu cong service (chac chan dung han).
#    Backend co the TU DOI cong (8080 ban do Tomcat -> 8081/8082...), nen quet ca dai 8080-8090.
$ports = @(3000) + (8080..8090)
foreach ($p in $ports) {
  Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
      $pid2 = $_
      $pname = (Get-Process -Id $pid2 -ErrorAction SilentlyContinue).ProcessName
      # Chi dung tien trinh CUA APP (java/node) - tranh giet Tomcat cua ban tren 8080.
      if ($pname -and $pname -notmatch '^(java|javaw|node)$') {
        if ($p -eq 8080) { return }   # 8080 do Tomcat la giu -> bo qua
      }
      try { Stop-Process -Id $pid2 -Force; $stopped++; Write-Host "  Da dung service o cong $p ($pname, PID $pid2)" -ForegroundColor Yellow } catch {}
    }
}

Write-Host ""
Write-Host "App da dung ($stopped tien trinh). MySQL van chay." -ForegroundColor Green
Write-Host "Mo lai app: .\run" -ForegroundColor DarkGray
