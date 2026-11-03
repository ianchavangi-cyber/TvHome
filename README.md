# TV Home (Apple TV style launcher for Hisense HiSmart 2K ATV4)

Build: Android Studio > Open this folder > wait for Gradle sync > Build > Build APK(s).
Install: install.bat <TV_IP>
Undo: restore.bat <TV_IP>

Change dock apps / columns: app/src/main/res/values/config.xml
  columns = 4..7 (dock slots and grid columns)
  dock_apps = package names, left to right (replace ONSTREAM_PACKAGE_HERE)
Find a package name: adb shell pm list packages | findstr /i name

Font: Outfit (SIL Open Font License), app/src/main/res/font/outfit.ttf
