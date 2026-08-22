@echo off
setlocal
cd /d "%~dp0"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows-build.ps1" %*
set "EXITCODE=%ERRORLEVEL%"

echo.
if not "%EXITCODE%"=="0" (
  echo Build/deploy failed with exit code %EXITCODE%.
) else (
  echo Build/deploy completed successfully.
)
pause
exit /b %EXITCODE%
