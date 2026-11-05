<#
.SYNOPSIS
  다음집 내비 실측(노트북) — 경로 안내 이벤트(onRouteStarted·onDestinationArrived)와 배송지 직접 추가(내비 장소 검색 → 목적지 받기).

.DESCRIPTION
  CI(GitHub Actions)의 공식 에뮬레이터는 내비가 지도를 그리지 못하고, 경로·검색 화면을 요청하면 내비가 다시 초기화된다
  (docs/pleos/navihelper-sdk.md '내비 실험'). 노트북 에뮬레이터는 내비가 지도·경로·도착 예정 시각을 그렸으므로 여기서 잰다.

  먼저 run-probe.ps1 -Phase auto 로 빌드·에뮬레이터 기동까지 끝낸 뒤(에뮬레이터는 켜 둔 채) 저장소 루트에서:

    powershell -ExecutionPolicy Bypass -File scripts\probe\navi-lab.ps1 -Phase events
    powershell -ExecutionPolicy Bypass -File scripts\probe\navi-lab.ps1 -Phase place -PickSeconds 180

  events : 사람 손 없이 돈다(약 4분). 앱이 내비에 경로를 요청하고, 에뮬레이터 위치를 목적지까지 옮긴다(adb emu geo fix).
           onRoutingCompleted·onRouteStarted·onDestinationArrived·onNavigationStatus가 오는지 기록한다.
  place  : 주행 화면의 '내비에서 찾기'(서울역)를 스크립트로 누른 뒤, 사람이 내비 검색 결과 하나를 목적지로 정할 때까지
           -PickSeconds 초 기다린다. 검색 화면이 안 뜨면 '내비 열어 고르기'로 다시 연다(내비에서 직접 검색해 목적지로 정하면 된다).
           배송지가 추가됐는지(NS|PLACE|Added) 기록한다. 자동화 도구로 돌릴 때는 이 명령을 백그라운드로 띄우고,
           shots 폴더의 화면을 보며 adb shell input tap 으로 고르면 된다.

  결과는 docs\probe-results\<시각>-navi-lab-<phase>\ 에 저장된다(SUMMARY.md, 로그, 화면). 사용자 이름·폴더 경로는 <user>로 바꾼다.
  기록 대상은 공공 장소 검색 결과와 에뮬레이터 위치뿐이다(개인 데이터 없음).
#>
[CmdletBinding()]
param(
    [ValidateSet('events', 'place')]
    [string]$Phase = 'events',
    [string]$OutDir = '',
    [int]$PickSeconds = 180,
    [string]$Keyword = '서울역'
)

$ErrorActionPreference = 'Stop'
$Repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$OnWindows = ($env:OS -eq 'Windows_NT')
$Exe = if ($OnWindows) { '.exe' } else { '' }
$Pkg = 'app.nextstop'
$Stamp = Get-Date -Format 'yyyyMMdd-HHmm'
if (-not $OutDir) { $OutDir = Join-Path $Repo "docs/probe-results/$Stamp-navi-lab-$Phase" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$Results = New-Object System.Collections.Generic.List[object]
$Timeline = New-Object System.Text.StringBuilder

function Hide-Personal([string]$text) {
    if (-not $text) { return $text }
    $t = $text
    if ($env:USERPROFILE) { $t = $t.Replace($env:USERPROFILE, '<user-home>') }
    if ($env:USERNAME) { $t = $t -replace [regex]::Escape($env:USERNAME), '<user>' }
    if ($env:COMPUTERNAME) { $t = $t -replace [regex]::Escape($env:COMPUTERNAME), '<host>' }
    if (-not $OnWindows -and $env:HOME -and $env:HOME.Length -gt 1) { $t = $t.Replace($env:HOME, '<user-home>') }
    return $t
}

function Save-Text([string]$name, [string]$text) {
    [System.IO.File]::WriteAllText((Join-Path $OutDir $name), (Hide-Personal $text), (New-Object System.Text.UTF8Encoding($false)))
}

function Mark([string]$msg) {
    $line = "{0} {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg
    [void]$Timeline.AppendLine($line)
    Write-Host $line -ForegroundColor Cyan
}

function Add-Result([string]$Item, [string]$Status, [string]$Detail) {
    $Results.Add([pscustomobject]@{ Item = $Item; Status = $Status; Detail = (Hide-Personal $Detail) })
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
    Write-Host ("[{0}] {1} - {2}" -f $Status, $Item, $Detail) -ForegroundColor $color
}

function Invoke-Native([string]$File, [string[]]$ArgList) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $out = & $File @ArgList 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $old }
    return (($out | Out-String).TrimEnd())
}

$Sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path (Join-Path $_ "platform-tools/adb$Exe")) } | Select-Object -First 1
if (-not $Sdk) { Write-Host 'Android SDK를 찾지 못했습니다(run-probe.ps1 먼저)' -ForegroundColor Red; exit 1 }
$Adb = Join-Path $Sdk "platform-tools/adb$Exe"
function Adb([string[]]$a) { return Invoke-Native $Adb $a }
function Sh([string]$cmd) { return Invoke-Native $Adb @('shell', $cmd) }
# 한글 인자는 Windows PowerShell → adb shell에서 깨지므로 퍼센트 인코딩으로 넘긴다(앱의 AdbArg가 푼다)
function Enc([string]$s) { return 'u:' + [uri]::EscapeDataString($s) }
function Lab([string]$extras) { Sh "am broadcast -a app.nextstop.LAB -p $Pkg $extras" | Out-Null }
function Shot([string]$name) {
    Sh 'screencap -p /sdcard/ns-shot.png' | Out-Null
    Adb @('pull', '/sdcard/ns-shot.png', (Join-Path $OutDir "$name.png")) | Out-Null
}
function Top {
    $line = (Sh 'dumpsys activity activities') -split "`r?`n" | Where-Object { $_ -match 'topResumedActivity|mResumedActivity' } | Select-Object -First 1
    if ($line) { return $line.Trim() } else { return '?' }
}
function Log-Of([string]$tag) { return Adb @('logcat', '-d', '-v', 'time', '-s', "$($tag):I") }
function GeoFix([double]$lat, [double]$lng) {
    Adb @('emu', 'geo', 'fix', $lng.ToString([Globalization.CultureInfo]::InvariantCulture), $lat.ToString([Globalization.CultureInfo]::InvariantCulture)) | Out-Null
}

$devices = Adb @('devices')
if ($devices -notmatch "`n\S+\s+device") { Write-Host '연결된 에뮬레이터가 없습니다(run-probe.ps1 -Phase auto 로 먼저 띄우기)' -ForegroundColor Red; exit 1 }
$Apk = Join-Path $Repo 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path $Apk)) { Write-Host "APK가 없습니다: $Apk (run-probe.ps1 -Phase auto 가 빌드)" -ForegroundColor Red; exit 1 }
Mark '설치'
Adb @('install', '-r', '-g', $Apk) | Out-Null
Adb @('logcat', '-c') | Out-Null

# ================================================================ events
if ($Phase -eq 'events') {
    Sh "am start -n $Pkg/.probe.ProbeActivity --es lab navi" | Out-Null
    Start-Sleep -Seconds 8
    Lab '--es cmd state'; Lab '--es cmd loc'; Start-Sleep -Seconds 3
    $m = [regex]::Matches((Log-Of 'NSPROBE'), 'onCurrentLocationInfo\|at=([-0-9.]+),([-0-9.]+)')
    if ($m.Count -gt 0) { $lat0 = [double]$m[$m.Count - 1].Groups[1].Value; $lng0 = [double]$m[$m.Count - 1].Groups[2].Value }
    else { $lat0 = 37.412721; $lng0 = 127.094681; Mark '현재 위치를 못 받아 기본값' }
    $inv = [Globalization.CultureInfo]::InvariantCulture
    $lat1 = $lat0 + 0.0036
    Mark ("현재 위치 {0},{1} → 경로 요청 {2},{1} (약 400m 북쪽)" -f $lat0.ToString('F6', $inv), $lng0.ToString('F6', $inv), $lat1.ToString('F6', $inv))
    GeoFix $lat0 $lng0
    Lab ("--es cmd route --es lat {0} --es lng {1} --es name lab-dest" -f $lat1.ToString('F6', $inv), $lng0.ToString('F6', $inv))
    Start-Sleep -Seconds 10; Shot '1-route'
    Mark "맨 앞 화면: $(Top)"
    # 내비가 다시 초기화되면 앱의 연결이 끊긴 채 남을 수 있다(CI 2회차) → 다시 연결해 상태를 묻는다
    Lab '--es cmd reconnect'; Start-Sleep -Seconds 5
    Lab '--es cmd state'; Lab '--es cmd dest'; Start-Sleep -Seconds 3
    for ($i = 1; $i -le 20; $i++) { GeoFix ($lat0 + 0.0036 * $i / 20) $lng0; Start-Sleep -Seconds 3 }
    Mark '목적지 좌표에 도착(geo fix 20단계, 3초 간격)'
    for ($k = 1; $k -le 5; $k++) { GeoFix $lat1 $lng0; Start-Sleep -Seconds 4 }
    Shot '2-arrived'
    Lab '--es cmd state'; Lab '--es cmd dest'; Start-Sleep -Seconds 4
    Lab '--es cmd reconnect'; Start-Sleep -Seconds 5; Lab '--es cmd state'; Start-Sleep -Seconds 3
    Lab '--es cmd cancel'; Start-Sleep -Seconds 3

    $log = Log-Of 'NSPROBE'
    Save-Text 'lab-log.txt' $log
    foreach ($ev in @('onRoutingCompleted', 'onRouteStarted', 'onDestinationArrived', 'onDestinationChanged', 'onRouteCancelled')) {
        $hit = ($log -split "`r?`n" | Where-Object { $_ -match "lab\.event\|$ev" } | Select-Object -First 1)
        $status = if ($hit) { 'PASS' } elseif ($ev -eq 'onRouteCancelled' -or $ev -eq 'onDestinationChanged') { 'INFO' } else { 'FAIL' }
        Add-Result $ev $status $(if ($hit) { ($hit -split 'lab\.event\|')[1] } else { '안 옴' })
    }
    $status = ([regex]::Matches($log, 'onNavigationStatus\|([A-Z_]+)') | ForEach-Object { $_.Groups[1].Value }) -join ' → '
    Add-Result '내비 상태 변화' 'INFO' $(if ($status) { $status } else { '없음' })
    $modes = ([regex]::Matches($log, 'naviMode=([A-Z_]+), isRouting=(\w+)') | ForEach-Object { "$($_.Groups[1].Value)/$($_.Groups[2].Value)" }) -join ' → '
    Add-Result '안내 모드/경로 중' 'INFO' $(if ($modes) { $modes } else { '없음' })
}

# ================================================================ place
if ($Phase -eq 'place') {
    $drive = "am start -n $Pkg/.drive.DriveActivity"
    Sh "$drive --es mode vehicle --ez reset true --ez clear_stops true" | Out-Null
    Start-Sleep -Seconds 8; Shot '0-plan-empty'
    Mark "'내비에서 찾기' $Keyword"
    Sh "$drive --es place $(Enc $Keyword)" | Out-Null
    Start-Sleep -Seconds 10; Shot '1-search'
    $top = Top
    Mark "맨 앞 화면: $top"
    if ($top -match $Pkg) {
        Mark "검색 화면이 안 떴다 → '내비 열어 고르기'"
        Sh "$drive --ez pick true" | Out-Null
        Start-Sleep -Seconds 10; Shot '1-pick'
        Mark "맨 앞 화면: $(Top)"
    }
    Write-Host ''
    Write-Host "지금 에뮬레이터 내비에서: $Keyword 검색 결과 하나를 눌러 목적지로 정하세요(안내 시작/목적지 설정). ${PickSeconds}초 기다립니다." -ForegroundColor Yellow
    $deadline = (Get-Date).AddSeconds($PickSeconds)
    $added = $null
    $n = 0
    while ((Get-Date) -lt $deadline -and -not $added) {
        Start-Sleep -Seconds 5
        $n++
        if ($n % 4 -eq 0) { Shot ("2-wait-{0:D2}" -f $n) }
        $added = ((Log-Of 'NextStop') -split "`r?`n" | Where-Object { $_ -match 'NS\|PLACE\|(Added|Merged)' } | Select-Object -First 1)
    }
    if (-not $added) {
        # 앱이 뒤에 있으면 목적지 변경 알림을 못 받았을 수 있다 → 앱으로 돌아와 한 번 더 묻는다(checkPendingPlace)
        Mark '앱으로 돌아와 목적지 다시 묻기'
        Sh $drive | Out-Null
        Start-Sleep -Seconds 6
        $added = ((Log-Of 'NextStop') -split "`r?`n" | Where-Object { $_ -match 'NS\|PLACE\|(Added|Merged)' } | Select-Object -First 1)
    }
    Sh $drive | Out-Null
    Start-Sleep -Seconds 5; Shot '3-plan-after'
    $log = Log-Of 'NextStop'
    Save-Text 'app-log.txt' $log
    Add-Result '배송지 직접 추가(내비에서 고른 곳 → 배송지)' $(if ($added) { 'PASS' } else { 'FAIL' }) $(if ($added) { $added.Trim() } else { "${PickSeconds}초 안에 추가 없음" })
    $flow = ($log -split "`r?`n" | Where-Object { $_ -match 'NS\|PLACE\|' } | ForEach-Object { ($_ -split 'NS\|PLACE\|')[1] }) -join ' → '
    Add-Result '앱 흐름(NS|PLACE)' 'INFO' $flow
    $nav = ($log -split "`r?`n" | Where-Object { $_ -match 'NS\|NAV\|' } | Select-Object -Last 3) -join ' / '
    if ($nav) { Add-Result '내비 요청 결과' 'INFO' $nav }
}

$crash = Adb @('logcat', '-d', '-b', 'crash')
if ($crash -match $Pkg) { Save-Text 'crash.txt' $crash; Add-Result '앱 크래시' 'FAIL' 'crash.txt 확인' } else { Add-Result '앱 크래시' 'PASS' '없음' }

$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# 내비 실측 ($Phase, $Stamp, 노트북 에뮬레이터)")
[void]$md.AppendLine('')
[void]$md.AppendLine('| 항목 | 결과 | 상세 |')
[void]$md.AppendLine('|---|---|---|')
foreach ($r in $Results) {
    $d = ($r.Detail -replace '\|', '/' -replace "`r?`n", ' ')
    if ($d.Length -gt 220) { $d = $d.Substring(0, 220) + '…' }
    [void]$md.AppendLine("| $($r.Item) | $($r.Status) | $d |")
}
[void]$md.AppendLine('')
[void]$md.AppendLine('## 순서')
[void]$md.AppendLine('```')
[void]$md.Append($Timeline.ToString())
[void]$md.AppendLine('```')
Save-Text 'SUMMARY.md' $md.ToString()
Write-Host $md.ToString()
Write-Host "결과 폴더: $(Hide-Personal $OutDir)"
