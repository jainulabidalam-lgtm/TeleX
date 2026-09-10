Write-Host "=== Searching Downloads folder ==="
if (Test-Path "C:\Users\alama\Downloads") {
    Get-ChildItem "C:\Users\alama\Downloads" -ErrorAction SilentlyContinue | Select-Object Name | Out-String | Write-Host
}
