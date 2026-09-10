$ErrorActionPreference = "Stop"

$jdkPath = "F:\Telex\.jdk17\jdk-17.0.10+7"
$sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
$gradleBinDir = "C:\Users\alama\.gradle\wrapper\dists\gradle-9.2.0-bin\11i5gvueggl8a5cioxuftxrik\gradle-9.2.0\bin"

$env:JAVA_HOME = $jdkPath
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:PATH = "$jdkPath\bin;$gradleBinDir;$sdkRoot\platform-tools;$sdkRoot\cmdline-tools\latest\bin;" + $env:PATH

Write-Host "=== 1. Checking ADB Devices ==="
& "$sdkRoot\platform-tools\adb.exe" devices

if (-not (Test-Path "gradlew.bat")) {
    Write-Host "`n=== 2. Generating gradlew wrapper ==="
    & "$gradleBinDir\gradle.bat" wrapper
}

Write-Host "`n=== 3. Running gradlew.bat installDebug ==="
& ".\gradlew.bat" installDebug
