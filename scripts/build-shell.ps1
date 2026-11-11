# Builds the SPARKY Shell APK -> shell\app\build\outputs\apk\debug\app-debug.apk
# Toolchain lives on D: (JDK 17, Android SDK 34, Gradle 8.7); Gradle cache also on D: (E: is small).
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\sparky-tools\jdk17'
$env:ANDROID_HOME = 'D:\sparky-tools\android-sdk'
$env:GRADLE_USER_HOME = 'D:\sparky-tools\gradle-home'
Push-Location (Join-Path $PSScriptRoot '..\shell')
try {
  & D:\sparky-tools\gradle-8.7\bin\gradle.bat --no-daemon -q assembleDebug
  if ($LASTEXITCODE -ne 0) { throw "gradle failed ($LASTEXITCODE)" }
  Get-Item app\build\outputs\apk\debug\app-debug.apk | Select-Object FullName, Length
} finally { Pop-Location }
