@echo off
REM Bi danh "pause" -> DUNG app (giong stop). PowerShell: go  .\pause
REM (Luu y: trong CMD, "pause" la lenh co san cua Windows; hay dung "stop" hoac ".\pause.cmd")
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-all.ps1" %*
