$ErrorActionPreference = "Stop"

$sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
$cmdlineToolsDir = "$sdkRoot\cmdline-tools"
$latestDir = "$cmdlineToolsDir\latest"
$jdkPath = "F:\Telex\.jdk17\jdk-17.0.10+7"

Write-Host "=== Setting JAVA_HOME ==="
$env:JAVA_HOME = $jdkPath
$env:PATH = "$jdkPath\bin;" + $env:PATH
Write-Host "JAVA_HOME set to: $env:JAVA_HOME"

Write-Host "`n=== Creating Android SDK directories ==="
if (-not (Test-Path $sdkRoot)) { New-Item -ItemType Directory -Path $sdkRoot -Force | Out-Null }
if (-not (Test-Path $cmdlineToolsDir)) { New-Item -ItemType Directory -Path $cmdlineToolsDir -Force | Out-Null }

$zipUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$zipPath = "$env:TEMP\commandlinetools-win.zip"
$extractTemp = "$env:TEMP\cmdline-tools-temp"

if (-not (Test-Path "$latestDir\bin\sdkmanager.bat")) {
    Write-Host "`n=== Downloading Android Command Line Tools ==="
    Write-Host "URL: $zipUrl"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath -UseBasicParsing
    Write-Host "Downloaded to $zipPath"

    Write-Host "`n=== Extracting Command Line Tools ==="
    if (Test-Path $extractTemp) { Remove-Item -Path $extractTemp -Recurse -Force }
    Expand-Archive -Path $zipPath -DestinationPath $extractTemp -Force

    if (Test-Path $latestDir) { Remove-Item -Path $latestDir -Recurse -Force }

    # The zip contains a folder named 'cmdline-tools'. Move its contents to 'latest'
    if (Test-Path "$extractTemp\cmdline-tools") {
        Move-Item -Path "$extractTemp\cmdline-tools" -Destination $latestDir -Force
    } else {
        New-Item -ItemType Directory -Path $latestDir -Force | Out-Null
        Get-ChildItem -Path $extractTemp | Move-Item -Destination $latestDir -Force
    }
    Remove-Item -Path $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item -Path $extractTemp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Extracted to $latestDir"
} else {
    Write-Host "Command line tools already present at $latestDir"
}

Write-Host "`n=== Environment Variables Setup ==="
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:PATH = "$latestDir\bin;$sdkRoot\platform-tools;" + $env:PATH

[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdkRoot, "User")
[Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $sdkRoot, "User")
[Environment]::SetEnvironmentVariable("JAVA_HOME", $jdkPath, "User")

# Update User PATH
$userPath = [Environment]::GetEnvironmentVariable("PATH", "User")
$pathsToAdd = @("$jdkPath\bin", "$latestDir\bin", "$sdkRoot\platform-tools")
foreach ($p in $pathsToAdd) {
    if ($userPath -notlike "*$p*") {
        $userPath = "$p;$userPath"
    }
}
[Environment]::SetEnvironmentVariable("PATH", $userPath, "User")
Write-Host "Environment variables ANDROID_HOME, JAVA_HOME, and PATH updated successfully!"

Write-Host "`n=== Accepting Android Licenses ==="
$sdkManager = "$latestDir\bin\sdkmanager.bat"
$cmdLine = "cmd.exe /c `"echo y | `"$sdkManager`" --sdk_root=`"$sdkRoot`" --licenses`""
Invoke-Expression $cmdLine

Write-Host "`n=== Installing Platform-Tools (adb), Platform 34, Build-Tools 34.0.0 ==="
& "$sdkManager" "--sdk_root=$sdkRoot" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

Write-Host "`n=== Installation Completed Successfully! ==="
