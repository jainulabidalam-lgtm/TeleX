$adb = "E:\Client\data\obs-plugins\droidcam-obs\adb\adb.exe"

Write-Host "=== Checking Connected Devices ==="
& $adb devices

Write-Host "`n=== Building Debug APK (gradlew assembleDebug) ==="
cmd /c "gradlew.bat assembleDebug"

$apkPath = "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apkPath) {
    Write-Host "`n=== Installing APK to device R9ZY10T3XHL ==="
    cmd /c "`"$adb`" -s R9ZY10T3XHL install -r $apkPath"
} else {
    Write-Host "ERROR: APK build failed or APK not found at $apkPath"
}
