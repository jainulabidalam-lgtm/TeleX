Write-Host "=== Subdirectories in AppData\Local\Google ==="
Get-ChildItem "C:\Users\alama\AppData\Local\Google" -ErrorAction SilentlyContinue | Select-Object FullName | Out-String | Write-Host

Write-Host "=== Subdirectories in AppData\Local\Programs ==="
Get-ChildItem "C:\Users\alama\AppData\Local\Programs" -ErrorAction SilentlyContinue | Select-Object FullName | Out-String | Write-Host

Write-Host "=== Subdirectories in AppData\Local\Android ==="
if (Test-Path "C:\Users\alama\AppData\Local\Android") {
    Get-ChildItem "C:\Users\alama\AppData\Local\Android" -ErrorAction SilentlyContinue | Select-Object FullName | Out-String | Write-Host
} else {
    Write-Host "No Android folder in AppData\Local"
}
