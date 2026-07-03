@echo off
REM Dung app: backend + frontend. (MySQL van chay)
REM PowerShell: go  .\stop    | CMD: go  stop
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-all.ps1" %*
