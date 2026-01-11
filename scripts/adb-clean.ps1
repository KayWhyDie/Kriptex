param(
  [string]$Package = "com.ivor.kriptex",
  [string]$Apk = "",
  [string]$Adb = "C:\Users\KWD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"

# PowerShell 7+ can treat native stderr as terminating errors in some setups.
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
  $PSNativeCommandUseErrorActionPreference = $false
}

function Get-LatestDebugApk {
  $apkDir = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug"
  $item = Get-ChildItem "$apkDir\*.apk" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $item) { throw "No debug APK found at $apkDir. Build first (./gradlew :app:assembleDebug)." }
  return $item.FullName
}

if (-not (Test-Path $Adb)) {
  throw "adb.exe not found at: $Adb"
}

& $Adb start-server | Out-Null

function Wait-DeviceOnline {
  param([string]$DeviceId, [int]$TimeoutSeconds = 20)
  for ($i = 0; $i -lt $TimeoutSeconds; $i++) {
    try {
      $state = (& $Adb -s $DeviceId get-state 2>$null).Trim()
      if ($state -eq "device") { return $true }
    } catch {
      # ignore
    }
    Start-Sleep -Seconds 1
  }
  return $false
}

$devices = & $Adb devices | Select-String "\tdevice$" | ForEach-Object { ($_.Line -split "\t")[0] }
if (-not $devices) {
  throw "No adb devices attached. Run '$Adb devices' to verify."
}

if ([string]::IsNullOrWhiteSpace($Apk)) {
  $Apk = Get-LatestDebugApk
}

Write-Host "Installing: $Apk"
$failed = @()
foreach ($d in $devices) {
  Write-Host ""
  Write-Host "=== $d ==="

  try {
    if (-not (Wait-DeviceOnline -DeviceId $d -TimeoutSeconds 20)) {
      throw "Device stayed offline: $d"
    }

    # Stop app if running.
    & $Adb -s $d shell am force-stop $Package | Out-Null

    # Uninstall (best-effort). Some OEMs can intermittently fail uninstall; we'll still proceed
    # with a replace install (-r) below.
    & $Adb -s $d uninstall $Package 2>$null | Out-Null

    # Best-effort cleanup of external app dirs which can sometimes survive uninstall (varies by OEM).
    & $Adb -s $d shell rm -rf "/sdcard/Android/data/$Package" "/sdcard/Android/media/$Package" "/sdcard/Android/obb/$Package" | Out-Null

    # Verify it's gone.
    $pkgs = & $Adb -s $d shell pm list packages $Package
    if ($pkgs -match [regex]::Escape($Package)) {
      throw "Uninstall verification failed: package still present ($Package)"
    }

    # Install fresh (use -r to handle cases where uninstall fails). Some devices reject `-g`.
    function Try-Install {
      param(
        [string[]]$Args
      )
      $out = & $Adb -s $d install @Args "$Apk" 2>&1
      return @{ Code = $LASTEXITCODE; Out = $out }
    }

    $result = Try-Install -Args @('-r','-g')
    if ($result.Code -ne 0) {
      $outText = ($result.Out | Out-String)

      if ($outText -match "INSTALL_FAILED_USER_RESTRICTED") {
        Write-Host $outText
        throw "Install blocked by device policy/UI prompt. On the phone, enable Developer options -> 'Install via USB' (MIUI may also require allowing USB installs in Security) and accept any install prompts."
      }

      if ($outText -match "INSTALL_GRANT_RUNTIME_PERMISSIONS" -or $outText -match "INSTALL_GRANT_RUNTIME_PERMISSIONS flag") {
        Write-Host "Device rejected -g; retrying install without -g..."
        $result = Try-Install -Args @('-r')
        $outText = ($result.Out | Out-String)
      }

      if ($result.Code -ne 0 -and ($outText -match "INSTALL_PARSE_FAILED_NOT_APK" -or $outText -match "Failed to load asset path")) {
        Write-Host "Streamed install failed; retrying with --no-streaming..."
        $result = Try-Install -Args @('--no-streaming','-r')
        $outText = ($result.Out | Out-String)
      }

      if ($result.Code -ne 0) {
        if ($outText) { Write-Host $outText }
        throw "Install failed (exit=$($result.Code))"
      }
    }
  } catch {
    Write-Host "FAILED on ${d}: $($_.Exception.Message)"
    $failed += $d
  }
}

if ($failed.Count -gt 0) {
  Write-Host ""
  Write-Host ("Clean reinstall failed on: " + ($failed -join ', '))
  exit 1
}

exit 0
