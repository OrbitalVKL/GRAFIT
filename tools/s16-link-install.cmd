@echo off
rem Grafit - lien S-16 : installe la diffusion en arriere-plan (cachee, au demarrage de la session).
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Unblock-File -Path '%~dp0s16-link.ps1'"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0s16-link.ps1" -Test
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0s16-link.ps1" -Install
echo.
pause
