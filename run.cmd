@echo off
REM Chay toan bo he thong: bot + backend + frontend + mysql
REM   CMD        : go   run
REM   PowerShell : go   .\run     (PHAI co .\ — neu go 'run' tran, PowerShell chay nham run.cmd cua nvm tren PATH)
REM   Dung app   : .\stop  (hoac .\pause)
REM   Doi model  : .\run -Model qwen3:8b   |  Bo buoc: .\run -SkipOllama -SkipMysql
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" %*
