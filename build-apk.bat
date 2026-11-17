@echo off
rem Bouwt de Android-app (apk) met Docker, zonder Android Studio.
rem Resultaat: android\app\build\outputs\apk\debug\app-debug.apk
cd /d "%~dp0android"
docker run --rm -v "%cd%":/project -v semtv-gradle-cache:/root/.gradle -w /project mingc/android-build-box:latest gradle assembleDebug --no-daemon
if errorlevel 1 (
  echo.
  echo BOUWEN MISLUKT - zie de foutmelding hierboven.
  pause
  exit /b 1
)
echo.
echo Klaar: %cd%\app\build\outputs\apk\debug\app-debug.apk
echo Installeren op een paneel met USB-foutopsporing:  adb install -r app\build\outputs\apk\debug\app-debug.apk
pause
