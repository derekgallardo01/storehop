<#
.SYNOPSIS
  Capture high-resolution marketing screenshots of every StoreHop screen, on the
  Pixel phone + tablet AVDs, in both light and dark themes.

.DESCRIPTION
  For each AVD x theme it: boots the emulator, installs the debug + androidTest
  APKs, sets the system theme, enables a clean "demo mode" status bar, runs
  `ScreenshotTourTest` (which seeds curated demo data offline and captures each
  screen), then pulls the PNGs into docs/marketing/screenshots/<device>/<theme>/.

  The tour uses the instrumented test graph (LocalOnly auth, no Firebase, no
  network), so it needs no Google account and is fully deterministic.

.EXAMPLE
  pwsh scripts/capture-android-screenshots.ps1
  pwsh scripts/capture-android-screenshots.ps1 -Avds Pixel_Phone -Themes dark
#>
[CmdletBinding()]
param(
  [string[]] $Avds   = @('Pixel_Phone', 'Pixel_Tablet'),
  [ValidateSet('light','dark')] [string[]] $Themes = @('light','dark'),
  [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
$repo   = Split-Path -Parent $PSScriptRoot
$sdk    = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb    = Join-Path $sdk 'platform-tools\adb.exe'
$emu    = Join-Path $sdk 'emulator\emulator.exe'
$pkg    = 'com.storehop.app'
$testPkg = "$pkg.test"
$runner = "$pkg.HiltTestRunner"
$testClass = 'com.storehop.app.screenshots.ScreenshotTourTest'
$remoteDir = "/sdcard/Android/data/$pkg/files/screenshots"

foreach ($t in @($adb,$emu)) { if (-not (Test-Path $t)) { throw "Not found: $t" } }

function DeviceLabel([string]$avd) { if ($avd -match 'Tablet') { 'tablet' } else { 'phone' } }

function Wait-Boot {
  & $adb wait-for-device | Out-Null
  for ($i=0; $i -lt 120; $i++) {
    $b = (& $adb shell getprop sys.boot_completed) 2>$null
    if ("$b".Trim() -eq '1') { return }
    Start-Sleep 2
  }
  throw 'Emulator did not finish booting in time.'
}

function Set-Theme([string]$mode) {
  $night = if ($mode -eq 'dark') { 'yes' } else { 'no' }
  & $adb shell cmd uimode night $night | Out-Null
  Start-Sleep 1
}

function Enable-DemoBar {
  try {
    & $adb shell settings put global sysui_demo_allowed 1 | Out-Null
    $b = "am broadcast -a com.android.systemui.demo"
    & $adb shell "$b -e command enter" | Out-Null
    & $adb shell "$b -e command clock -e hhmm 1200" | Out-Null
    & $adb shell "$b -e command battery -e level 100 -e plugged false" | Out-Null
    & $adb shell "$b -e command network -e wifi show -e level 4" | Out-Null
    & $adb shell "$b -e command network -e mobile show -e datatype none -e level 4" | Out-Null
    & $adb shell "$b -e command notifications -e visible false" | Out-Null
  } catch { Write-Warning "demo-mode status bar not applied: $_" }
}
function Disable-DemoBar { try { & $adb shell "am broadcast -a com.android.systemui.demo -e command exit" | Out-Null } catch {} }

# --- Build the APKs once (two-step; matches CI's KSP/Hilt workaround) ---
if (-not $SkipBuild) {
  Write-Host '== Building debug + androidTest APKs ==' -ForegroundColor Cyan
  & "$repo\gradlew.bat" :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
  if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
}
$appApk  = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $repo 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
foreach ($a in @($appApk,$testApk)) { if (-not (Test-Path $a)) { throw "APK missing: $a (run without -SkipBuild)" } }

foreach ($avd in $Avds) {
  $label = DeviceLabel $avd
  Write-Host "== Booting $avd ($label) ==" -ForegroundColor Cyan
  $p = Start-Process -FilePath $emu -ArgumentList @("@$avd", '-no-boot-anim', '-no-snapshot', '-gpu', 'swiftshader_indirect', '-noaudio') -PassThru
  try {
    Wait-Boot
    & $adb shell settings put global window_animation_scale 0 | Out-Null
    & $adb shell settings put global transition_animation_scale 0 | Out-Null
    & $adb shell settings put global animator_duration_scale 0 | Out-Null
    Write-Host '   installing APKs...' -ForegroundColor DarkGray
    & $adb install -r -t $appApk  | Out-Null
    & $adb install -r -t $testApk | Out-Null

    foreach ($theme in $Themes) {
      Write-Host "== $label / $theme ==" -ForegroundColor Green
      Set-Theme $theme
      Enable-DemoBar
      & $adb shell rm -rf $remoteDir | Out-Null
      & $adb shell am instrument -w -e class $testClass "$testPkg/$runner"
      $outDir = Join-Path $repo "docs\marketing\screenshots\$label\$theme"
      New-Item -ItemType Directory -Force -Path $outDir | Out-Null
      Get-ChildItem $outDir -Filter *.png -ErrorAction SilentlyContinue | Remove-Item -Force
      $pulled = Join-Path $env:TEMP 'shot_pull'
      if (Test-Path $pulled) { Remove-Item -Recurse -Force $pulled }
      & $adb pull $remoteDir $pulled | Out-Null
      if (Test-Path $pulled) {
        # adb pull dir semantics vary (contents vs nested folder) — grab PNGs recursively.
        Get-ChildItem $pulled -Recurse -Filter *.png | Move-Item -Destination $outDir -Force
        Remove-Item -Recurse -Force $pulled
      }
      $n = (Get-ChildItem $outDir -Filter *.png -ErrorAction SilentlyContinue).Count
      Write-Host "   -> $n PNGs in $outDir" -ForegroundColor Green
      Disable-DemoBar
    }
  }
  finally {
    Write-Host "   shutting down $avd" -ForegroundColor DarkGray
    try { & $adb emu kill | Out-Null } catch {}
    try { if ($p -and -not $p.HasExited) { $p.Kill() } } catch {}
    Start-Sleep 3
  }
}

Write-Host 'Done. Screenshots under docs/marketing/screenshots/.' -ForegroundColor Cyan
