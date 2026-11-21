@echo off
setlocal
echo FileHub AIO - Google OAuth publisher setup (run once)
echo This utility is for the FileHub publisher, not for ordinary users.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -STA -File "%~dp0Setup-GooglePublisherOAuth.ps1"
if errorlevel 1 (
  echo.
  echo Publisher setup was not completed. Review the message above.
  pause
  exit /b 1
)
echo.
echo Please verify the GitHub Actions run before testing the Google drive.
pause
