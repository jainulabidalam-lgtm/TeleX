Write-Host "=== Checking Gradle ==="
cmd /c "gradle -v 2>&1" | Out-String | Write-Host

Write-Host "=== Checking Android Studio Gradle Wrapper / Distribution ==="
Get-ChildItem "C:\Program Files", "C:\Users\alama\.gradle" -Filter "gradlew.bat" -Recurse -ErrorAction SilentlyContinue | Select-Object FullName | Out-String | Write-Host
