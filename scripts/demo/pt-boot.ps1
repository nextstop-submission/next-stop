<#
.SYNOPSIS
  PT 현장 시연 준비 한 번에: 공식 에뮬레이터 Cold boot → 부팅 완료 + 내비 안정 대기 → APK 설치 → 시연 신호 모드로 앱을 맨 앞에.
  발표 시작 직전(장비 연결할 때) 실행하고, 덱을 말하는 동안 뒤에서 돌게 둔다 — docs/pt/시연_운영.md

.EXAMPLE
  scripts\demo\pt-boot.cmd
  scripts\demo\pt-boot.cmd -Avd Pleos_Connect_v2_1_0 -Gpu host -SettleSec 30
#>
param(
    [string]$Avd = 'Pleos_Connect_v2_1_0',
    [string]$Gpu = 'host',
    [int]$SettleSec = 30,
    [string]$Apk = ''
)
$ErrorActionPreference = 'Continue'
$Repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") | Where-Object { $_ -and (Test-Path (Join-Path $_ 'platform-tools\adb.exe')) } | Select-Object -First 1
if (-not $sdk) { Write-Host 'Android SDK(adb)를 찾지 못했습니다.' -ForegroundColor Red; exit 1 }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emu = Join-Path $sdk 'emulator\emulator.exe'
if (-not $Apk) { $Apk = Get-ChildItem (Join-Path $Repo 'apk') -Filter '*release.apk' | Select-Object -First 1 -ExpandProperty FullName }
if (-not $Apk -or -not (Test-Path $Apk)) { Write-Host 'apk 폴더에서 release APK를 찾지 못했습니다.' -ForegroundColor Red; exit 1 }

$t0 = Get-Date
function Say([string]$m) { Write-Host ("[{0,4:N0}초] {1}" -f ((Get-Date) - $t0).TotalSeconds, $m) }

& $adb start-server | Out-Null   # adb 키가 에뮬레이터보다 먼저 준비돼야 한다(CI 2회차 교훈)
Say "에뮬레이터 Cold boot: $Avd (-gpu $Gpu)"
Start-Process -FilePath $emu -ArgumentList @('-avd', $Avd, '-no-snapshot-load', '-gpu', $Gpu) | Out-Null

& $adb wait-for-device
do { Start-Sleep -Seconds 2; $b = (& $adb shell getprop sys.boot_completed 2>$null) } until ("$b".Trim() -eq '1')
$bootSec = [int]((Get-Date) - $t0).TotalSeconds
Say "부팅 완료 ($bootSec초) — 내비가 스스로 지도를 불러오는 동안 $SettleSec초 대기"
Start-Sleep -Seconds $SettleSec

Say "APK 설치: $(Split-Path $Apk -Leaf)"
& $adb install -r -g $Apk | Out-Null
& $adb shell am start -n app.nextstop/.drive.DriveActivity --es mode demo --ez reset true | Out-Null
Start-Sleep -Seconds 3
$top = (& $adb shell dumpsys activity activities | Select-String 'ResumedActivity' | Select-Object -First 1)
Say "맨 앞 화면: $("$top".Trim())"

Write-Host ''
Write-Host ("  준비 끝 (부팅 {0}초, 전체 {1:N0}초)  " -f $bootSec, ((Get-Date) - $t0).TotalSeconds) -ForegroundColor Black -BackgroundColor Green
Write-Host '  맨 앞이 내비라면 아래 줄 다음집 아이콘으로 돌아온 뒤 운행 시작.'
