@echo off
REM Chay he thong: backend + frontend + MySQL
REM   CMD        : go   run
REM   PowerShell : go   .\run     (PHAI co .\ - neu go 'run' tran, PowerShell chay nham run.cmd cua nvm tren PATH)
REM   Dung app   : .\stop  (hoac .\pause)
REM   Bo MySQL   : .\run -SkipMysql
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" %*
