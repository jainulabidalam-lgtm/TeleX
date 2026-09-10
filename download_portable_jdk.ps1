$zipUrl = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jdk_x64_windows_hotspot_17.0.10_7.zip"
$zipPath = "f:\Telex\.jdk17.zip"
$extractDir = "f:\Telex\.jdk17"

if (-not (Test-Path "$extractDir\jdk-17.0.10+7\bin\java.exe")) {
    Write-Host "Downloading portable OpenJDK 17..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath
    
    Write-Host "Extracting OpenJDK 17..."
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force
    Remove-Item $zipPath -Force
}

$javaHome = "f:\Telex\.jdk17\jdk-17.0.10+7"
Write-Host "JDK 17 Ready at $javaHome"
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

cmd /c "java -version"
