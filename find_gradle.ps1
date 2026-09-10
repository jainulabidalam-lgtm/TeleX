Write-Host "Checking .gradle/wrapper/dists..."
if (Test-Path "C:\Users\alama\.gradle\wrapper\dists") {
    Get-ChildItem -Path "C:\Users\alama\.gradle\wrapper\dists" -Recurse -Filter "gradle.bat" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName | Out-String | Write-Host
}
