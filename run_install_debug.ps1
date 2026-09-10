$ErrorActionPreference = "Stop"

$jdkPath = "F:\Telex\.jdk17\jdk-17.0.10+7"
$sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"

$env:JAVA_HOME = $jdkPath
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:PATH = "$jdkPath\bin;$sdkRoot\platform-tools;$sdkRoot\cmdline-tools\latest\bin;" + $env:PATH

Write-Host "=== 1. Checking connected devices via adb ==="
& "$sdkRoot\platform-tools\adb.exe" devices

Write-Host "`n=== 2. Checking gradlew executable ==="
$gradleBat = "C:\Users\alama\.gradle\wrapper\dists\gradle-9.2.0-bin\11i5gvueggl8a5cioxuftxrik\gradle-9.2.0\bin\gradle.bat"

if (-not (Test-Path "gradlew.bat")) {
    Write-Host "Generating gradlew wrapper..."
    cmd.exe /c "`"$gradleBat`" wrapper"
}

Write-Host "`n=== 3. Executing ./gradlew installDebug ==="
if (Test-Path "gradlew.bat") {
    cmd.exe /c "gradlew.bat installDebug --stacktrace"
} else {
    cmd.exe /c "`"$gradleBat`" installDebug --stacktrace"
}
