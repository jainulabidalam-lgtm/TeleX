$msiPath = "$env:USERPROFILE\Downloads\OpenJDK17U-jdk_x64_windows.msi"
$url = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"

Write-Host "=== Downloading Eclipse Temurin JDK 17 MSI Installer ==="
Write-Host "Destination: $msiPath"
curl.exe -L -o $msiPath $url

if (Test-Path $msiPath) {
    $fileSize = (Get-Item $msiPath).Length
    Write-Host "Downloaded successfully! File size: $fileSize bytes"

    Write-Host "`n=== Launching Temurin JDK 17 MSI Installer with JAVA_HOME feature enabled ==="
    # Running msiexec with ADDLOCAL=FeatureMain,FeatureJavaHome,FeatureJarFileRunWith,FeaturePath enables JAVA_HOME and PATH setting
    Start-Process msiexec.exe -ArgumentList "/i `"$msiPath`" ADDLOCAL=FeatureMain,FeatureJavaHome,FeatureJarFileRunWith,FeaturePath" -Wait

    Write-Host "`n=== Installation Finished ==="
} else {
    Write-Host "ERROR: Failed to download MSI installer."
}
