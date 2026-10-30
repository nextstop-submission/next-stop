<#
.SYNOPSIS
  시연 보조: 배송 N건(D → 주행거리 +k → P → 문 열기 → 문 닫기 → D)을 adb로 재생하고, 원하면 화면을 녹화한다.

.EXAMPLE
  # 시연 신호 모드로 앱을 새로 띄우고 3건 재생
  powershell -ExecutionPolicy Bypass -File scripts\demo\demo.ps1 -Launch -Stops 3

  # 같은 흐름을 녹화(시연 영상 가산점용) — 결과: demo-output\nextstop-demo-<시각>.mp4
  powershell -ExecutionPolicy Bypass -File scripts\demo\demo.ps1 -Launch -Stops 5 -Record

  # D를 넣는 순간 플랫폼 주행 제한을 켠다(실차처럼 2줄 화면·정적 확인 흐름을 재현)
  powershell -ExecutionPolicy Bypass -File scripts\demo\demo.ps1 -Launch -Stops 2 -Regulation

  # VHAL 주입 경로(실측에서 PASS일 때만)
  powershell -ExecutionPolicy Bypass -File scripts\demo\demo.ps1 -Mode vhal -Stops 1

.NOTES
  Virtual Console로 직접 조작하는 시연이 가장 설득력 있다(docs/pleos/virtual-console.md). 이 스크립트는 리허설·녹화·예비용.
#>
param(
    [ValidateSet('broadcast', 'vhal')]
    [string]$Mode = 'broadcast',
    [int]$Stops = 1,
    [double]$GapSec = 1.5,
    [switch]$Launch,
    [switch]$Record,
    [switch]$Regulation,
    [double]$OdoStartKm = 12000,
    [double]$OdoStepKm = 2
)
$ErrorActionPreference = 'Continue'
$Repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$OnWindows = ($env:OS -eq 'Windows_NT')
$Exe = if ($OnWindows) { '.exe' } else { '' }
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") | Where-Object { $_ -and (Test-Path (Join-Path $_ "platform-tools/adb$Exe")) } | Select-Object -First 1
if (-not $sdk) { Write-Host 'Android SDK(adb)를 찾지 못했습니다.' -ForegroundColor Red; exit 1 }
$adb = Join-Path $sdk "platform-tools/adb$Exe"
$pkg = 'app.nextstop'
$regOn = 'cmd hmg_car_service --service drivingstate driving_regulation on'
$regOff = 'cmd hmg_car_service --service drivingstate driving_regulation off'
$odo = $OdoStartKm

function Shell([string]$cmd) { & $adb shell $cmd | Out-Null }

function Send([string]$what) {
    if ($Mode -eq 'broadcast') {
        switch ($what) {
            'P' { Shell "am broadcast -a app.nextstop.SIGNAL -p $pkg --es gear P" }
            'D' { Shell "am broadcast -a app.nextstop.SIGNAL -p $pkg --es gear D" }
            'open' { Shell "am broadcast -a app.nextstop.SIGNAL -p $pkg --es door open" }
            'close' { Shell "am broadcast -a app.nextstop.SIGNAL -p $pkg --es door closed" }
            'odo' { Shell ("am broadcast -a app.nextstop.SIGNAL -p $pkg --ef odo {0:0.0}" -f $script:odo) }
        }
    } else {
        switch ($what) {
            'P' { Shell 'cmd car_service inject-vhal-event 0x11400400 4' }
            'D' { Shell 'cmd car_service inject-vhal-event 0x11400400 8' }
            'open' { Shell 'cmd car_service inject-vhal-event 0x16400b00 1 1' }
            'close' { Shell 'cmd car_service inject-vhal-event 0x16400b00 1 0' }
            'odo' { } # VHAL 경로에서는 Virtual Console의 Odometer 카드를 쓴다
        }
    }
    Write-Host "-> $what"
    Start-Sleep -Milliseconds ([int]($GapSec * 1000))
}

function Drive {
    Send 'D'
    if ($Regulation) { Shell $regOn; Write-Host '   (주행 제한 ON)' }
}

function Park {
    if ($Regulation) { Shell $regOff; Write-Host '   (주행 제한 OFF)' }
    Send 'P'
}

$recordFile = $null
if ($Record) {
    $outDir = Join-Path $Repo 'demo-output'
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $recordFile = Join-Path $outDir "nextstop-demo-$stamp.mp4"
    & $adb shell rm -f /sdcard/nextstop-demo.mp4 | Out-Null
    # 기기에서 mp4로 바로 녹화(최대 180초). 끝나면 SIGINT로 정상 종료해 파일을 마무리한다.
    $recArgs = @('shell', 'screenrecord', '--time-limit', '180', '/sdcard/nextstop-demo.mp4')
    $rec = if ($OnWindows) { Start-Process -FilePath $adb -ArgumentList $recArgs -PassThru -WindowStyle Hidden } else { Start-Process -FilePath $adb -ArgumentList $recArgs -PassThru }
    Start-Sleep -Seconds 2
    Write-Host "녹화 시작 (최대 180초)"
}

if ($Launch) {
    $m = if ($Mode -eq 'broadcast') { 'demo' } else { 'vehicle' }
    Shell "am start -n $pkg/.drive.DriveActivity --es mode $m --ez reset true --ez autostart true"
    Start-Sleep -Seconds 3
    if ($Mode -eq 'broadcast') { Send 'odo' }
    Drive
}

for ($i = 1; $i -le $Stops; $i++) {
    $script:odo += $OdoStepKm
    Send 'odo'
    Park
    Send 'open'
    Send 'close'
    Drive
    Start-Sleep -Seconds 2
}
if ($Regulation) { Shell $regOff }

if ($Record) {
    Start-Sleep -Seconds 2
    & $adb shell pkill -INT screenrecord | Out-Null
    Start-Sleep -Seconds 2
    if ($rec -and -not $rec.HasExited) { $rec.WaitForExit(5000) | Out-Null }
    & $adb pull /sdcard/nextstop-demo.mp4 $recordFile | Out-Null
    if (Test-Path $recordFile) { Write-Host "녹화 저장: $recordFile" -ForegroundColor Green }
    else { Write-Host '녹화 파일을 가져오지 못했습니다. 공식 대안: adb exec-out screenrecord --output-format=h264 - (docs/pleos 참고)' -ForegroundColor Yellow }
}
