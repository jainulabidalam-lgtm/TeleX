Write-Host "Checking for Eclipse Adoptium installation..."
if (Test-Path "C:\Program Files\Eclipse Adoptium") {
    Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Recurse -Filter "java.exe" -ErrorAction SilentlyContinue | Select-Object FullName | Out-String | Write-Host
} else {
    Write-Host "Not installed yet"
}
