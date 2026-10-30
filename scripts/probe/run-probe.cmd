@echo off
rem 다음집 실측 실행: run-probe.cmd -Phase auto  /  run-probe.cmd -Phase manual -OutDir <폴더>
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-probe.ps1" %*
