$javaCmd = Get-Command java -ErrorAction SilentlyContinue
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
$sdkmanagerCmd = Get-Command sdkmanager -ErrorAction SilentlyContinue

Write-Host "================ ENVIRONMENT CHECK ================"
Write-Host "JAVA_HOME: $env:JAVA_HOME"
if ($javaCmd) { Write-Host "Java Binary: $($javaCmd.Source)" } else { Write-Host "Java Binary: NOT FOUND on PATH" }

Write-Host "ANDROID_HOME: $env:ANDROID_HOME"
Write-Host "ANDROID_SDK_ROOT: $env:ANDROID_SDK_ROOT"

if ($adbCmd) { Write-Host "ADB Binary: $($adbCmd.Source)" } else { Write-Host "ADB Binary: NOT FOUND on PATH" }
if ($sdkmanagerCmd) { Write-Host "sdkmanager Binary: $($sdkmanagerCmd.Source)" } else { Write-Host "sdkmanager Binary: NOT FOUND on PATH" }

$localAppDataSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
Write-Host "AppData Sdk Folder ($localAppDataSdk): $(Test-Path $localAppDataSdk)"

$cmdlineToolsDir = Join-Path $localAppDataSdk "cmdline-tools"
Write-Host "cmdline-tools Folder ($cmdlineToolsDir): $(Test-Path $cmdlineToolsDir)"
