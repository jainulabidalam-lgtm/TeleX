Write-Host "=== Searching Program Files for java.exe ==="
cmd /c "where /r `"C:\Program Files`" java.exe 2>nul" | Out-String | Write-Host

Write-Host "=== Searching Program Files (x86) for java.exe ==="
cmd /c "where /r `"C:\Program Files (x86)`" java.exe 2>nul" | Out-String | Write-Host
