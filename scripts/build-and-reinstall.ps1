param(
  [string]$Package = "com.ivor.kriptex",
  [string]$Adb = "C:\Users\KWD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"

# PowerShell 7+ can treat native stderr as terminating errors in some setups.
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
  $PSNativeCommandUseErrorActionPreference = $false
}

Write-Host "Building debug APK..."
$gradle = Join-Path $PSScriptRoot "..\gradlew.bat"
& $gradle ":app:assembleDebug" "--no-daemon"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Clean reinstall on all connected devices..."
$adbExit = 0
try {
  & "$PSScriptRoot\adb-clean.ps1" -Package $Package -Adb $Adb
  $adbExit = $LASTEXITCODE
} catch {
  Write-Host (($_ | Out-String).TrimEnd())
  $adbExit = $LASTEXITCODE
  if ($adbExit -eq $null) { $adbExit = 1 }
}
if ($adbExit -ne 0) { exit $adbExit }

exit 0
