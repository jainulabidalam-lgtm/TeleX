$ErrorActionPreference = "Stop"

$sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
$cmdlineToolsDir = "$sdkRoot\cmdline-tools"
$latestDir = "$cmdlineToolsDir\latest"
$jdkPath = "F:\Telex\.jdk17\jdk-17.0.10+7"

Write-Host "=== 1. JAVA_HOME & Paths Setup ==="
$env:JAVA_HOME = $jdkPath
$env:PATH = "$jdkPath\bin;$latestDir\bin;$sdkRoot\platform-tools;" + $env:PATH

[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdkRoot, "User")
[Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $sdkRoot, "User")
[Environment]::SetEnvironmentVariable("JAVA_HOME", $jdkPath, "User")

Write-Host "ANDROID_HOME set to: $sdkRoot"
Write-Host "JAVA_HOME set to: $jdkPath"

Write-Host "`n=== 2. Directory Creation ==="
if (-not (Test-Path $sdkRoot)) { New-Item -ItemType Directory -Path $sdkRoot -Force | Out-Null }
if (-not (Test-Path $cmdlineToolsDir)) { New-Item -ItemType Directory -Path $cmdlineToolsDir -Force | Out-Null }

$zipPath = "$env:TEMP\cmdline-tools.zip"
$extractTemp = "$env:TEMP\cmdline-tools-extract"

if (-not (Test-Path "$latestDir\bin\sdkmanager.bat")) {
    Write-Host "`n=== 3. Downloading Android Command Line Tools via curl ==="
    $zipUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    curl.exe -L -o $zipPath $zipUrl
    Write-Host "Download finished!"

    Write-Host "`n=== 4. Extracting ZIP ==="
    if (Test-Path $extractTemp) { Remove-Item -Path $extractTemp -Recurse -Force }
    Expand-Archive -Path $zipPath -DestinationPath $extractTemp -Force

    if (Test-Path $latestDir) { Remove-Item -Path $latestDir -Recurse -Force }

    if (Test-Path "$extractTemp\cmdline-tools") {
        Move-Item -Path "$extractTemp\cmdline-tools" -Destination $latestDir -Force
    } else {
        New-Item -ItemType Directory -Path $latestDir -Force | Out-Null
        Get-ChildItem -Path $extractTemp | Move-Item -Destination $latestDir -Force
    }
    Remove-Item -Path $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item -Path $extractTemp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Command Line Tools extracted to $latestDir"
} else {
    Write-Host "Command Line Tools already exists at $latestDir"
}

Write-Host "`n=== 5. Updating User PATH Environment Variable ==="
$userPath = [Environment]::GetEnvironmentVariable("PATH", "User")
$pathsToAdd = @("$jdkPath\bin", "$latestDir\bin", "$sdkRoot\platform-tools")
foreach ($p in $pathsToAdd) {
    if ($userPath -notlike "*$p*") {
        $userPath = "$p;$userPath"
    }
}
[Environment]::SetEnvironmentVariable("PATH", $userPath, "User")
Write-Host "User PATH updated!"

Write-Host "`n=== 6. Accepting Android Licenses ==="
$sdkManager = "$latestDir\bin\sdkmanager.bat"
cmd.exe /c "echo y | `"$sdkManager`" --sdk_root=`"$sdkRoot`" --licenses"

Write-Host "`n=== 7. Installing platform-tools (adb), platforms;android-34, build-tools;34.0.0 ==="
& "$sdkManager" "--sdk_root=$sdkRoot" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

Write-Host "`n=== 8. Checking ADB Devices ==="
$adbBin = "$sdkRoot\platform-tools\adb.exe"
& "$adbBin" devices

Write-Host "`n=== SDK Setup Completed Successfully ==="
