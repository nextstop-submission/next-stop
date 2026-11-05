@echo off
rem PT 현장: 에뮬레이터 Cold boot → APK 설치 → 시연 모드 앱 (docs/pt/시연_운영.md)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pt-boot.ps1" %*
pause
