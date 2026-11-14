@echo off
set "ANDROID_SDK_ROOT=C:\Users\mhuec\.gemini\antigravity-cli\brain\7056c590-1839-4696-892a-34e5a4460498\scratch\env\sdk"
set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
set "ANDROID_PREFS_ROOT=C:\Users\mhuec\.gemini\antigravity-cli\brain\7056c590-1839-4696-892a-34e5a4460498\scratch\env\android-prefs"
set "ANDROID_AVD_HOME=C:\Users\mhuec\.gemini\antigravity-cli\brain\7056c590-1839-4696-892a-34e5a4460498\scratch\env\android-prefs\.android\avd"
set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"

tasklist /FI "IMAGENAME eq qemu-system-x86_64.exe" 2>NUL | find /I "qemu-system-x86_64.exe" >NUL
if errorlevel 1 (
    del /f /q "%ANDROID_AVD_HOME%\GeoPdfPhone.avd\*.lock" 2>nul
    for /d %%D in ("%ANDROID_AVD_HOME%\GeoPdfPhone.avd\*.lock") do rmdir /s /q "%%D" 2>nul
    start "Android Emulator - GeoPdfPhone" "%ANDROID_SDK_ROOT%\emulator\emulator.exe" -avd GeoPdfPhone -no-boot-anim -gpu host
)

echo Waiting for emulator to finish booting...
"%ADB%" wait-for-device
:waitloop
for /f "tokens=*" %%a in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set BOOT=%%a
if not "%BOOT%"=="1" (
    ping -n 3 127.0.0.1 >nul
    goto waitloop
)

"%ADB%" install -r -g "C:\Users\mhuec\projects\mapper\app\build\outputs\apk\debug\app-debug.apk"
"%ADB%" emu geo fix -112.4685 34.5400
"%ADB%" shell am start -n net.mhu.mapper.geopdf/com.mapper.geopdf.ui.MainActivity
