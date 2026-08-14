<#
.SYNOPSIS
  Record the ~30–45s guided StoreHop walkthrough on the Pixel phone AVD and
  post-process it into an MP4 + GIF with ffmpeg.

.DESCRIPTION
  Boots the emulator, installs the debug + androidTest APKs, sets the theme and
  a clean demo-mode status bar, starts `adb screenrecord`, runs `DemoFlowTest`
  (which seeds demo data offline and drives the guided flow), stops recording,
  pulls the raw mp4, and encodes docs/marketing/video/walkthrough.{mp4,gif}.

.EXAMPLE
  pwsh scripts/record-android-video.ps1
  pwsh scripts/record-android-video.ps1 -Theme dark -Avd Pixel_Phone
#>
[CmdletBinding()]
param(
  [string] $Avd = 'Pixel_Phone',
  [ValidateSet('light','dark')] [string] $Theme = 'light',
  [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$sdk  = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$emu  = Join-Path $sdk 'emulator\emulator.exe'
$pkg  = 'com.storehop.app'
$runner = "$pkg.HiltTestRunner"
$testClass = 'com.storehop.app.screenshots.DemoFlowTest'
$remoteMp4 = '/sdcard/walk.mp4'

foreach ($t in @($adb,$emu)) { if (-not (Test-Path $t)) { throw "Not found: $t" } }
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) { throw 'ffmpeg not on PATH.' }

function Wait-Boot { & $adb wait-for-device | Out-Null; for ($i=0;$i -lt 120;$i++){ if ("$(& $adb shell getprop sys.boot_completed)".Trim() -eq '1'){return}; Start-Sleep 2 }; throw 'boot timeout' }

if (-not $SkipBuild) {
  Write-Host '== Building APKs ==' -ForegroundColor Cyan
  & "$repo\gradlew.bat" :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
  if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
}
$appApk  = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $repo 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'

Write-Host "== Booting $Avd ==" -ForegroundColor Cyan
$p = Start-Process -FilePath $emu -ArgumentList @("@$Avd", '-no-boot-anim', '-no-snapshot', '-gpu', 'swiftshader_indirect', '-noaudio') -PassThru
try {
  Wait-Boot
  & $adb install -r -t $appApk  | Out-Null
  & $adb install -r -t $testApk | Out-Null
  $night = if ($Theme -eq 'dark') { 'yes' } else { 'no' }
  & $adb shell cmd uimode night $night | Out-Null
  try {
    & $adb shell settings put global sysui_demo_allowed 1 | Out-Null
    $b = 'am broadcast -a com.android.systemui.demo'
    & $adb shell "$b -e command enter" | Out-Null
    & $adb shell "$b -e command clock -e hhmm 1200" | Out-Null
    & $adb shell "$b -e command battery -e level 100 -e plugged false" | Out-Null
    & $adb shell "$b -e command network -e wifi show -e level 4" | Out-Null
    & $adb shell "$b -e command notifications -e visible false" | Out-Null
  } catch {}

  & $adb shell rm -f $remoteMp4 | Out-Null
  Write-Host '== Recording walkthrough ==' -ForegroundColor Green
  $rec = Start-Job -ScriptBlock { param($adb,$remote) & $adb shell screenrecord --bit-rate 16000000 --time-limit 70 $remote } -ArgumentList $adb, $remoteMp4
  Start-Sleep 2  # let the recorder spin up

  & $adb shell am instrument -w -e class $testClass "$pkg.test/$runner"

  Start-Sleep 1
  # Graceful stop so the MP4 is finalized (fallback: let --time-limit end it).
  try { & $adb shell pkill -INT screenrecord | Out-Null } catch {}
  try { Wait-Job $rec -Timeout 15 | Out-Null } catch {}
  Remove-Job $rec -Force -ErrorAction SilentlyContinue
  Start-Sleep 2

  $videoDir = Join-Path $repo 'docs\marketing\video'
  New-Item -ItemType Directory -Force -Path $videoDir | Out-Null
  $raw = Join-Path $videoDir 'walk_raw.mp4'
  & $adb pull $remoteMp4 $raw | Out-Null
  if (-not (Test-Path $raw)) { throw 'screenrecord produced no file.' }

  Write-Host '== Encoding mp4 + gif (ffmpeg) ==' -ForegroundColor Cyan
  $mp4 = Join-Path $videoDir 'walkthrough.mp4'
  $gif = Join-Path $videoDir 'walkthrough.gif'
  $pal = Join-Path $env:TEMP 'sh_palette.png'
  # Normalize to 30fps h264; keep native width (scale to even dims for yuv420p).
  & ffmpeg -y -i $raw -vf "fps=30,scale=trunc(iw/2)*2:trunc(ih/2)*2" -c:v libx264 -pix_fmt yuv420p -movflags +faststart $mp4
  & ffmpeg -y -i $mp4 -vf "fps=15,scale=480:-1:flags=lanczos,palettegen" $pal
  & ffmpeg -y -i $mp4 -i $pal -lavfi "fps=15,scale=480:-1:flags=lanczos[x];[x][1:v]paletteuse" $gif
  Remove-Item $raw,$pal -ErrorAction SilentlyContinue
  Write-Host "   -> $mp4" -ForegroundColor Green
  Write-Host "   -> $gif" -ForegroundColor Green
}
finally {
  try { & $adb emu kill | Out-Null } catch {}
  try { if ($p -and -not $p.HasExited) { $p.Kill() } } catch {}
}

Write-Host 'Done. Video under docs/marketing/video/.' -ForegroundColor Cyan
