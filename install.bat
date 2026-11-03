@echo off
REM Usage: install.bat <TV_IP>     (after building the APK in Android Studio)
if "%1"=="" (echo Usage: install.bat TV_IP & exit /b 1)
adb connect %1:5555
adb -s %1:5555 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s %1:5555 shell pm grant com.tvhome.launcher android.permission.ACCESS_FINE_LOCATION
echo.
echo Installed. The stock launcher is NOT disabled yet.
echo Press Home on the remote; if asked, choose "TV Home" and "Always".
