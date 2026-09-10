$gradleBat = "C:\Users\alama\.gradle\wrapper\dists\gradle-9.2.0-bin\11i5gvueggl8a5cioxuftxrik\gradle-9.2.0\bin\gradle.bat"
$adb = "E:\Client\data\obs-plugins\droidcam-obs\adb\adb.exe"

Write-Host "=== 1. Generating Gradle Wrapper ==="
cmd /c "`"$gradleBat`" wrapper"

Write-Host "`n=== 2. Running assembleDebug ==="
if (Test-Path "gradlew.bat") {
    cmd /c "gradlew.bat assembleDebug"
} else {
    cmd /c "`"$gradleBat`" assembleDebug"
}

Write-Host "`n=== 3. Checking Connected Devices ==="
& $adb devices

$apkPath = "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apkPath) {
    Write-Host "`n=== 4. Installing APK to device R9ZY10T3XHL ==="
    cmd /c "`"$adb`" -s R9ZY10T3XHL install -r $apkPath"
} else {
    Write-Host "`nERROR: APK build output not found at $apkPath"
}
