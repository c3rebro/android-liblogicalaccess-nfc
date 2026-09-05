@echo off
setlocal
cd /d "%~dp0"

:: Prefer pwsh.exe (PowerShell 7, always 64-bit on Windows).
:: If not found, use SysNative when running inside a WOW64 (32-bit) CMD process
:: so that $env:ProgramFiles and the registry resolve to their 64-bit views.
where /q pwsh.exe 2>nul
if %ERRORLEVEL%==0 (
    pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows-build.ps1" %*
    goto :report
)
if defined PROCESSOR_ARCHITEW6432 (
    "%SystemRoot%\SysNative\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows-build.ps1" %*
    goto :report
)
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows-build.ps1" %*

:report
set "EXITCODE=%ERRORLEVEL%"

echo.
if not "%EXITCODE%"=="0" (
  echo Build/deploy failed with exit code %EXITCODE%.
) else (
  echo Build/deploy completed successfully.
)
pause
exit /b %EXITCODE%
