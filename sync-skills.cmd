@echo off
REM Dong bo kho skill cua BOT theo "Khung nang luc" HIEN TAI cua grader, roi re-ingest RAG.
REM Chay SAU KHI sua Khung nang luc (backend phai dang chay).
REM   PowerShell: .\sync-skills        | CMD: sync-skills
REM Bot la repo RIENG (sibling ..\prm393-feedback-bot). Doi duong dan neu clone cho khac.
powershell -NoProfile -ExecutionPolicy Bypass -Command "cd '%~dp0..\prm393-feedback-bot'; .\.venv\Scripts\python.exe scripts\sync_bot_skills.py %*"
