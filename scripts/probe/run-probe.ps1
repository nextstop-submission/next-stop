<#
.SYNOPSIS
  다음집 에뮬레이터 실측 — 빌드, 에뮬레이터 기동, Virtual Console 접속, 설치, 자동 실측, 주행 제한 토글, VHAL 주입, 시연 모드 E2E, 결과 표 작성.

.DESCRIPTION
  Windows 노트북에서 저장소 루트 기준으로 실행한다.

    powershell -ExecutionPolicy Bypass -File scripts\probe\run-probe.ps1 -Phase auto
    powershell -ExecutionPolicy Bypass -File scripts\probe\run-probe.ps1 -Phase manual -OutDir <auto가 만든 폴더>

  auto   : 사람 손 없이 끝까지 돈다(약 5~10분, 에뮬레이터 첫 부팅 제외).
  manual : 사람이 Virtual Console로 기어·문을 조작하는 동안 120초간 신호를 기록한다.
           (Claude Code는 실행 중 출력을 실시간으로 보여 주지 못하므로, 조작 순서는 실행 전에 사람에게 알려 준다)

  결과는 docs\probe-results\<시각>\ 에 저장된다(SUMMARY.md, 로그, 스크린샷).
  저장 전에 Windows 사용자 이름·사용자 폴더 경로는 <user>로 바꾼다(익명 심사 대비).

  Linux(GitHub Actions 러너 등, pwsh 7)에서도 같은 스크립트가 돈다:
    pwsh scripts/probe/run-probe.ps1 -Phase auto -SkipBuild -Headless -BootMinutes 25
  -Headless 는 창 없이(-no-window) 소프트웨어 렌더링(swiftshader)으로 에뮬레이터를 띄운다 — 그래픽 카드 사양 미달 대비.
#>
[CmdletBinding()]
param(
    [ValidateSet('auto', 'manual')]
    [string]$Phase = 'auto',
    [string]$AvdName = '',
    [string]$OutDir = '',
    [switch]$SkipBuild,
    [int]$ManualSeconds = 120,
    # 저사양 모드(docs/pleos/emulator.md 대체안 A): host 또는 swiftshader_indirect
    [string]$Gpu = '',
    [switch]$Headless,
    [int]$BootMinutes = 6
)

$ErrorActionPreference = 'Stop'
$Repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
# Windows PowerShell 5.1에는 $IsWindows가 없다 → OS 환경 변수로 판단
$OnWindows = ($env:OS -eq 'Windows_NT')
$Exe = if ($OnWindows) { '.exe' } else { '' }
$Pkg = 'app.nextstop'
$Stamp = Get-Date -Format 'yyyyMMdd-HHmm'
if (-not $OutDir) { $OutDir = Join-Path $Repo "docs/probe-results/$Stamp-$Phase" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$Results = New-Object System.Collections.Generic.List[object]

function Write-Step([string]$msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

function Add-Result([string]$Item, [string]$Status, [string]$Detail) {
    $Results.Add([pscustomobject]@{ Item = $Item; Status = $Status; Detail = (Hide-Personal $Detail) })
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
    Write-Host ("[{0}] {1} - {2}" -f $Status, $Item, $Detail) -ForegroundColor $color
}

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
    $path = Join-Path $OutDir $name
    [System.IO.File]::WriteAllText($path, (Hide-Personal $text), (New-Object System.Text.UTF8Encoding($false)))
}

# 네이티브 명령 실행. Windows PowerShell 5.1에서 stderr가 예외로 바뀌지 않게 Continue로 감싼다.
function Invoke-Native([string]$Exe, [string[]]$ArgList, [int]$TimeoutSec = 0) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Exe @ArgList 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
    return [pscustomobject]@{ Code = $code; Out = (($out | Out-String).TrimEnd()) }
}

# ---------------------------------------------------------------- 도구 찾기
Write-Step '도구 확인'

function Find-Jdk {
    if (-not $OnWindows) {
        if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java'))) { return $env:JAVA_HOME }
        return $null
    }
    $candidates = @(
        $env:JAVA_HOME,
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
        "${env:ProgramFiles(x86)}\Android\Android Studio\jbr"
    ) + @(Get-ChildItem "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft" -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | ForEach-Object FullName)
    # Gradle 8.10.2는 Java 17~23에서만 돈다(최신 Android Studio jbr는 25라 빌드 실패) → release 파일의 주 버전으로 거른다.
    $ok = $candidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) } | Where-Object {
        $rel = Join-Path $_ 'release'
        if (-not (Test-Path $rel)) { return $true }
        $m = Select-String -Path $rel -Pattern 'JAVA_VERSION="(\d+)' | Select-Object -First 1
        -not $m -or ([int]$m.Matches[0].Groups[1].Value -ge 17 -and [int]$m.Matches[0].Groups[1].Value -le 23)
    }
    return $ok | Select-Object -First 1
}

function Find-Sdk {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
        Where-Object { $_ -and (Test-Path (Join-Path $_ "platform-tools/adb$Exe")) }
    return $candidates | Select-Object -First 1
}

$Jdk = Find-Jdk
$Sdk = Find-Sdk
if (-not $Jdk) { Add-Result 'JDK 17+' 'FAIL' 'Android Studio 설치(jbr) 또는 JAVA_HOME 필요'; }
else { $env:JAVA_HOME = $Jdk; $jv = (Invoke-Native (Join-Path $Jdk "bin/java$Exe") @('-version')).Out.Split("`n")[0]; Add-Result 'JDK' 'PASS' $jv }
if (-not $Sdk) {
    Add-Result 'Android SDK' 'FAIL' 'Android Studio SDK Manager로 SDK 설치 필요'
    Save-Text 'SUMMARY.md' ($Results | Format-Table -AutoSize | Out-String)
    exit 1
}
$Adb = Join-Path $Sdk "platform-tools/adb$Exe"
$Emulator = Join-Path $Sdk "emulator/emulator$Exe"
Add-Result 'Android SDK' 'PASS' $Sdk

# local.properties (gitignore 대상)
$sdkEscaped = if ($OnWindows) { $Sdk.Replace('\', '\\').Replace(':', '\:') } else { $Sdk }
[System.IO.File]::WriteAllText((Join-Path $Repo 'local.properties'), "sdk.dir=$sdkEscaped`n")

function Adb([string[]]$a) { return Invoke-Native $Adb $a }
function AdbShell([string]$cmd) { return Invoke-Native $Adb @('shell', $cmd) }

# ---------------------------------------------------------------- 에뮬레이터
function Get-OnlineDevice {
    $r = Adb @('devices')
    $line = $r.Out -split "`r?`n" | Where-Object { $_ -match "^\S+\s+device$" } | Select-Object -First 1
    if ($line) { return ($line -split '\s+')[0] }
    return $null
}

function Start-PleosEmulator {
    $serial = Get-OnlineDevice
    if ($serial) { Add-Result '에뮬레이터' 'PASS' "이미 연결됨: $serial"; return $serial }
    if (-not (Test-Path $Emulator)) { Add-Result '에뮬레이터' 'FAIL' 'emulator 없음 (SDK Manager > Android Emulator 설치)'; return $null }
    $avds = (Invoke-Native $Emulator @('-list-avds')).Out -split "`r?`n" | Where-Object { $_ -and $_ -notmatch '^INFO' }
    if (-not $avds) { Add-Result '에뮬레이터' 'FAIL' 'AVD 없음 (docs/pleos/emulator.md 대로 Pleos AVD 생성)'; return $null }
    $avd = if ($AvdName) { $AvdName } else { ($avds | Where-Object { $_ -match 'pleos' } | Select-Object -First 1) }
    if (-not $avd) { $avd = $avds | Select-Object -First 1 }
    # -Headless(CI·창 없음)는 소프트웨어 렌더링이 기본, -Gpu를 주면 그 값이 우선한다
    if ($Headless -and -not $Gpu) { $Gpu = 'swiftshader_indirect' }
    Write-Host "AVD 기동: $avd (cold boot$(if ($Gpu) { ", -gpu $Gpu" })$(if ($Headless) { ', 창 없음' }))"
    # Pleos 문서: Quick Boot면 VHAL 미동작 → 스냅샷 없이 부팅
    $emuArgs = @('-avd', $avd, '-no-snapshot-load')
    if ($Gpu) { $emuArgs += @('-gpu', $Gpu) }
    if ($Headless) { $emuArgs += @('-no-window', '-no-audio', '-no-boot-anim') }
    if ($OnWindows) { Start-Process -FilePath $Emulator -ArgumentList $emuArgs -WindowStyle Normal | Out-Null }
    else { Start-Process -FilePath $Emulator -ArgumentList $emuArgs | Out-Null }
    $bootStart = Get-Date
    $deadline = $bootStart.AddMinutes($BootMinutes)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $serial = Get-OnlineDevice
        if ($serial) {
            $boot = (AdbShell 'getprop sys.boot_completed').Out.Trim()
            if ($boot -eq '1') {
                $secs = [int]((Get-Date) - $bootStart).TotalSeconds
                Start-Sleep -Seconds 15; Add-Result '에뮬레이터' 'PASS' "$avd 부팅 완료 ${secs}초$(if ($Gpu) { " (-gpu $Gpu)" })"; return $serial
            }
        }
    }
    Add-Result '에뮬레이터' 'FAIL' "$avd ${BootMinutes}분 내 부팅 안 됨$(if ($Gpu) { " (-gpu $Gpu)" })"
    return $null
}

function Get-Log([string[]]$tags) {
    $spec = @('logcat', '-d', '-v', 'time', '-s') + ($tags | ForEach-Object { "$($_):I" })
    return (Adb $spec).Out
}

function Wait-Log([string]$pattern, [int]$seconds, [string[]]$tags = @('NSPROBE', 'NextStop')) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $log = Get-Log $tags
        if ($log -match $pattern) { return $true }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

function Take-Screenshot([string]$name) {
    AdbShell "screencap -p /sdcard/ns-shot.png" | Out-Null
    Adb @('pull', '/sdcard/ns-shot.png', (Join-Path $OutDir $name)) | Out-Null
}

function Send-Signal([string]$extraKey, [string]$value) {
    $type = if ($extraKey -eq 'odo') { '--ef' } else { '--es' }
    AdbShell "am broadcast -a app.nextstop.SIGNAL -p $Pkg $type $extraKey $value" | Out-Null
    Start-Sleep -Milliseconds 900
}

# ================================================================ manual
if ($Phase -eq 'manual') {
    Write-Step "수동 조작 기록 ($ManualSeconds 초)"
    if (-not (Get-OnlineDevice)) { Add-Result '에뮬레이터' 'FAIL' '연결된 에뮬레이터 없음 (auto 먼저 실행)'; exit 1 }
    Adb @('logcat', '-c') | Out-Null
    # 실차 신호 모드로 운행을 새로 시작한 뒤, 실측 화면을 띄워 모든 리스너가 같이 기록되게 한다.
    AdbShell "am start -n $Pkg/.drive.DriveActivity --es mode vehicle --ez reset true --ez autostart true" | Out-Null
    Start-Sleep -Seconds 3
    AdbShell "am start -n $Pkg/.probe.ProbeActivity --ez auto true" | Out-Null
    # 리스너 등록 때 오는 초기값 이벤트를 판정에서 빼기 위해 15초 기다린 뒤 로그를 비우고 기록을 시작한다.
    Start-Sleep -Seconds 15
    Adb @('logcat', '-c') | Out-Null
    Write-Host '지금 Virtual Console에서: Gear D → Odometer +2km → Gear P → Door Position(앞 왼쪽) Open → Close → Gear D (각 5초 이상 간격)'
    Start-Sleep -Seconds $ManualSeconds
    $log = Get-Log @('NSPROBE', 'NextStop')
    Save-Text 'manual-logcat.txt' $log
    Take-Screenshot 'manual-end.png'

    $gearP = $log -match 'NSPROBE\|gear\|update\.GEAR_SELECTION\|4 '
    $gearD = $log -match 'NSPROBE\|gear\|update\.GEAR_SELECTION\|8 '
    $doorOpen = $log -match 'NSPROBE\|door\|update\.ROW_1_LEFT\|[1-9]'
    $doorClose = $log -match 'NSPROBE\|door\|update\.ROW_1_LEFT\|0'
    $completed = ([regex]::Matches($log, 'NS\|EFFECT\|STOP_COMPLETED')).Count
    $odoSeen = ($log -split "`r?`n" | Where-Object { $_ -match 'NSPROBE\|odometer\|update\.registerDistanceDriven' } | Select-Object -Last 1)
    Add-Result 'Virtual Console Odometer → Pleos Odometer 수신' ($(if ($odoSeen) { 'PASS' } else { 'FAIL' })) "$odoSeen"
    $uxDuring = ($log -split "`r?`n" | Where-Object { $_ -match 'NSPROBE\|ux\|' } | ForEach-Object { ($_ -split '\|')[3] }) -join ' → '
    Add-Result '조작 중 플랫폼 주행 제한 변화' 'INFO' $(if ($uxDuring) { $uxDuring } else { '변화 없음(기어만으로는 제한이 안 켜짐)' })
    Add-Result 'Virtual Console 기어 P → 앱 수신' ($(if ($gearP) { 'PASS' } else { 'FAIL' })) 'GEAR_SELECTION=4'
    Add-Result 'Virtual Console 기어 D → 앱 수신' ($(if ($gearD) { 'PASS' } else { 'FAIL' })) 'GEAR_SELECTION=8'
    Add-Result 'Virtual Console 운전석 문 열림 → Pleos Door 수신' ($(if ($doorOpen) { 'PASS' } else { 'FAIL' })) 'update.ROW_1_LEFT>0'
    Add-Result 'Virtual Console 운전석 문 닫힘 → Pleos Door 수신' ($(if ($doorClose) { 'PASS' } else { 'FAIL' })) 'update.ROW_1_LEFT=0'
    Add-Result '실차 신호로 1건 완료(P·문·D)' ($(if ($completed -ge 1) { 'PASS' } else { 'FAIL' })) "STOP_COMPLETED x$completed"
    $other = ($log -split "`r?`n" | Where-Object { $_ -match 'NSPROBE\|door\|update\.' -and $_ -notmatch 'ROW_1_LEFT' } | Select-Object -First 5) -join ' / '
    if ($other) { Add-Result '다른 도어 영역 변화' 'INFO' $other }
}

# ================================================================ auto
if ($Phase -eq 'auto') {
    if (-not $SkipBuild) {
        Write-Step '빌드'
        Push-Location $Repo
        try {
            # Maven에는 Vehicle 2.0.3·2.3.2만 있다(릴리스 노트의 2.3.1은 미배포) → 대체 버전 재시도 없음.
            $gradlew = if ($OnWindows) { Join-Path $Repo 'gradlew.bat' } else { Join-Path $Repo 'gradlew' }
            $b = Invoke-Native $gradlew @(':core:test', ':app:assembleDebug', '--console=plain')
            $sdkUsed = (Select-String -Path (Join-Path $Repo 'gradle.properties') -Pattern '^pleosVehicleSdk=(.+)$').Matches[0].Groups[1].Value
            Save-Text 'build.log' $b.Out
            if ($b.Code -ne 0) { Add-Result '빌드' 'FAIL' 'build.log 확인'; Save-Text 'SUMMARY.md' ($Results | Out-String); exit 1 }
            Add-Result '빌드 (core 테스트 + debug APK)' 'PASS' "Vehicle SDK $sdkUsed"
        } finally { Pop-Location }
    }
    $Apk = Join-Path $Repo 'app/build/outputs/apk/debug/app-debug.apk'

    Write-Step '에뮬레이터'
    $serial = Start-PleosEmulator
    if (-not $serial) { Save-Text 'SUMMARY.md' ($Results | Out-String); exit 1 }

    Write-Step '환경 정보'
    $props = @('ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.build.type', 'ro.build.display.id') |
        ForEach-Object { "$_=" + (AdbShell "getprop $_").Out.Trim() }
    $pkgs = (AdbShell 'pm list packages').Out -split "`r?`n" | Where-Object { $_ -match 'pleos|navi|car' }
    $vsVer = (AdbShell 'dumpsys package ai.pleos.playground.service.vehicle').Out -split "`r?`n" | Where-Object { $_ -match 'versionName' } | Select-Object -First 1
    Save-Text 'environment.txt' (($props + '' + 'packages:' + $pkgs + '' + "vehicle service: $vsVer") -join "`n")
    Add-Result '에뮬레이터 이미지' 'INFO' ($props -join ', ')
    Add-Result 'Pleos Vehicle Service' ($(if ($vsVer) { 'PASS' } else { 'FAIL' })) ($(if ($vsVer) { $vsVer.Trim() } else { '패키지 없음' }))

    Write-Step 'Virtual Console (공식: adb forward tcp:8088 tcp:8088 → http://127.0.0.1:8088/)'
    Adb @('forward', 'tcp:8088', 'tcp:8088') | Out-Null
    try {
        $vc = Invoke-WebRequest -Uri 'http://127.0.0.1:8088/' -UseBasicParsing -TimeoutSec 10
        Add-Result 'Virtual Console 접속' 'PASS' "HTTP $($vc.StatusCode)"
        # 다음 단계(Virtual Console 조작 자동화) 분석용. Pleos 배포물이므로 저장소에는 커밋하지 않는다(.gitignore).
        $cache = Join-Path $Repo '.probe-cache'
        New-Item -ItemType Directory -Force -Path $cache | Out-Null
        [System.IO.File]::WriteAllText((Join-Path $cache 'virtual-console-index.html'), $vc.Content)
        $js = ([regex]::Matches($vc.Content, '(?:src|href)="([^"]+\.(?:js|json))"') | ForEach-Object { $_.Groups[1].Value }) -join ', '
        Add-Result 'Virtual Console 자원 목록(분석용)' 'INFO' $js
        # 조작 자동화 후보: 스크립트 안의 HTTP·WebSocket 경로만 뽑아 기록한다(파일 자체는 저장소·결과에 넣지 않는다).
        $paths = New-Object System.Collections.Generic.List[string]
        foreach ($m in [regex]::Matches($vc.Content, '(?:src)="([^"]+\.js)"')) {
            $u = $m.Groups[1].Value
            if ($u -notmatch '^https?://') { $u = 'http://127.0.0.1:8088/' + $u.TrimStart('/') }
            try {
                $body = (Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 10).Content
                [System.IO.File]::WriteAllText((Join-Path $cache ([IO.Path]::GetFileName($u))), $body)
                foreach ($e in [regex]::Matches($body, '["''`](/(?:api|ws|vhal|property|properties|set|socket)[A-Za-z0-9_/\-]*)["''`]')) { $paths.Add($e.Groups[1].Value) }
                foreach ($e in [regex]::Matches($body, '(?:fetch|axios\.(?:get|post|put))\(\s*["''`]([^"''`]+)')) { $paths.Add($e.Groups[1].Value) }
                if ($body -match 'WebSocket\(') { $paths.Add('(WebSocket 사용)') }
            } catch { }
        }
        Add-Result 'Virtual Console 요청 경로(분석용)' 'INFO' ((($paths | Select-Object -Unique) | Select-Object -First 30) -join ', ')
    } catch {
        Add-Result 'Virtual Console 접속' 'FAIL' $_.Exception.Message
    }

    Write-Step '설치'
    $i = Adb @('install', '-r', '-g', $Apk)
    Add-Result 'APK 설치' ($(if ($i.Out -match 'Success') { 'PASS' } else { 'FAIL' })) ($i.Out.Split("`n") | Select-Object -Last 1)
    $perms = (AdbShell "dumpsys package $Pkg").Out -split "`r?`n" | Where-Object { $_ -match 'permission\.(CAR_|NAVI_|CONTROL)' -and $_ -match 'granted=' }
    Save-Text 'permissions.txt' ($perms -join "`n")
    $denied = $perms | Where-Object { $_ -match 'granted=false' }
    Add-Result '권한 부여' ($(if ($denied) { 'WARN' } else { 'PASS' })) ($(if ($denied) { ($denied | ForEach-Object { $_.Trim() }) -join '; ' } else { "$($perms.Count)개 granted" }))

    Write-Step '자동 실측 (약 50초)'
    Adb @('logcat', '-c') | Out-Null
    AdbShell "am start -n $Pkg/.probe.ProbeActivity --ez auto true" | Out-Null
    $done = Wait-Log 'NSPROBE\|env\|auto_done' 75
    Take-Screenshot 'probe-auto.png'
    $log = Get-Log @('NSPROBE')

    function Has([string]$p) { return [bool]($log -match $p) }
    function Value([string]$key) {
        $m = [regex]::Match($log, "NSPROBE\|$([regex]::Escape($key))\|([^\r\n]*)")
        if ($m.Success) { return $m.Groups[1].Value } else { return '' }
    }
    Add-Result '실측 화면 자동 실행' ($(if ($done) { 'PASS' } else { 'FAIL' })) ''
    Add-Result 'Vehicle.initialize' ($(if (Has 'NSPROBE\|vehicle\|initialize\|ok') { 'PASS' } else { 'FAIL' })) (Value 'vehicle|initialize')
    Add-Result 'Door capability' ($(if ((Value 'door|capability') -match 'access=') { 'PASS' } else { 'FAIL' })) (Value 'door|capability')
    Add-Result 'Door 운전석 읽기' ($(if ((Value 'door|read.ROW_1_LEFT') -match '^\d') { 'PASS' } else { 'FAIL' })) (Value 'door|read.ROW_1_LEFT')
    Add-Result '기어 GEAR_SELECTION 읽기' ($(if ((Value 'gear|read.GEAR_SELECTION') -match '^\d') { 'PASS' } else { 'FAIL' })) (Value 'gear|read.GEAR_SELECTION')
    Add-Result '기어 CURRENT_GEAR 읽기' ($(if ((Value 'gear|read.CURRENT_GEAR') -match '^\d') { 'PASS' } else { 'INFO' })) (Value 'gear|read.CURRENT_GEAR')
    Add-Result 'AAOS PERF_ODOMETER 읽기' ($(if ((Value 'odometer|aaos.read.PERF_ODOMETER') -match '^\d') { 'PASS' } else { 'INFO' })) (Value 'odometer|aaos.read.PERF_ODOMETER')
    $odoCalls = ($log -split "`r?`n" | Where-Object { $_ -match 'NSPROBE\|odometer\|(call|update)\.' }) | ForEach-Object { ($_ -split 'NSPROBE\|odometer\|')[1] }
    Save-Text 'odometer-methods.txt' ((Value 'odometer|methods').Replace(' ; ', "`n") + "`n`n--- calls ---`n" + ($odoCalls -join "`n"))
    $odoNumeric = $odoCalls | Where-Object { $_ -match '\|\-?\d+(\.\d+)?<(Float|Double|Integer|Long)>' }
    $odoTotal = $odoCalls | Where-Object { $_ -match '^call\.getCurrentDistanceDriven\.cb0\|' } | Select-Object -First 1
    Add-Result 'Pleos Odometer 누적거리(getCurrentDistanceDriven)' ($(if ($odoTotal -match '\|\-?\d') { 'PASS' } else { 'FAIL' })) "$odoTotal"
    Add-Result 'Pleos Odometer 숫자 결과(전체)' ($(if ($odoNumeric) { 'PASS' } else { 'FAIL' })) (($odoNumeric | Select-Object -First 4) -join ' / ')
    Add-Result 'CarUxRestrictions' ($(if (Has 'NSPROBE\|ux\|requiresDistractionOptimization\|(true|false)') { 'PASS' } else { 'INFO' })) (Value 'ux|requiresDistractionOptimization')
    Add-Result 'NaviHelper.initialize' ($(if (Has 'NSPROBE\|navi\|initialize\|ok') { 'PASS' } else { 'FAIL' })) (Value 'navi|initialize')
    Add-Result 'requestRoute 호출(poiId 빈값)' ($(if ((Value 'navi|requestRoute.coords-empty-poi') -eq 'called') { 'PASS' } else { 'FAIL' })) (Value 'navi|requestRoute.coords-empty-poi')
    $naviEvents = ($log -split "`r?`n" | Where-Object { $_ -match 'NSPROBE\|navi\|(event|app_nav_event)\|' }) | ForEach-Object { ($_ -split '\|')[3] } | Select-Object -Unique
    $routed = $naviEvents | Where-Object { $_ -match 'onRoutingCompleted|onRouteStarted|RoutingCompleted|RouteStarted' }
    $naviErr = $naviEvents | Where-Object { $_ -match 'onError|Failed' }
    Add-Result 'requestRoute 결과 이벤트' ($(if ($routed) { 'PASS' } elseif ($naviErr) { 'FAIL' } else { 'WARN' })) ((($naviEvents | Select-Object -First 6) -join ' / '))
    Add-Result '내비 최근 목적지 가져오기' ($(if ((Value 'navi|destinations.recent') -match 'count=') { 'PASS' } else { 'WARN' })) (Value 'navi|destinations.recent')
    Add-Result '내비 즐겨찾기 가져오기' ($(if ((Value 'navi|destinations.bookmark') -match 'count=') { 'PASS' } else { 'WARN' })) (Value 'navi|destinations.bookmark')
    Save-Text 'probe-logcat.txt' $log

    Write-Step '주행 제한 토글 (공식 adb 명령)'
    $regOn = 'cmd hmg_car_service --service drivingstate driving_regulation on'
    $regOff = 'cmd hmg_car_service --service drivingstate driving_regulation off'
    Adb @('logcat', '-c') | Out-Null
    $r1 = AdbShell $regOn
    $uxOn = Wait-Log 'NSPROBE\|ux\|requiresDistractionOptimization\|true' 8 @('NSPROBE')
    Take-Screenshot 'regulation-on.png'
    Add-Result '주행 제한 ON → 앱 CarUxRestrictions 수신' ($(if ($uxOn) { 'PASS' } else { 'FAIL' })) (($r1.Out -split "`r?`n" | Select-Object -First 1))
    Adb @('logcat', '-c') | Out-Null
    $r2 = AdbShell $regOff
    $uxOff = Wait-Log 'NSPROBE\|ux\|requiresDistractionOptimization\|false' 8 @('NSPROBE')
    Add-Result '주행 제한 OFF → 앱 수신' ($(if ($uxOff) { 'PASS' } else { 'FAIL' })) (($r2.Out -split "`r?`n" | Select-Object -First 1))

    Write-Step 'VHAL 주입 시도 (기어·도어)'
    $root = Adb @('root')
    Start-Sleep -Seconds 3
    Adb @('wait-for-device') | Out-Null
    Add-Result 'adb root' 'INFO' $root.Out
    # 기어가 이미 P면 P 주입은 변화 이벤트가 없으므로 D부터 시작한다.
    $inject = @(
        @{ Name = '기어 D(시작)'; Cmd = 'cmd car_service inject-vhal-event 0x11400400 8'; Expect = 'NSPROBE\|gear\|update\.GEAR_SELECTION\|8 ' },
        @{ Name = '기어 P'; Cmd = 'cmd car_service inject-vhal-event 0x11400400 4'; Expect = 'NSPROBE\|gear\|update\.GEAR_SELECTION\|4 ' },
        @{ Name = '운전석 문 열림'; Cmd = 'cmd car_service inject-vhal-event 0x16400b00 1 1'; Expect = 'NSPROBE\|door\|update\.ROW_1_LEFT\|[1-9]' },
        @{ Name = '운전석 문 닫힘'; Cmd = 'cmd car_service inject-vhal-event 0x16400b00 1 0'; Expect = 'NSPROBE\|door\|update\.ROW_1_LEFT\|0' },
        @{ Name = '기어 D'; Cmd = 'cmd car_service inject-vhal-event 0x11400400 8'; Expect = 'NSPROBE\|gear\|update\.GEAR_SELECTION\|8 ' }
    )
    $injectLog = ''
    foreach ($step in $inject) {
        # 등록 직후 초기값 이벤트나 이전 단계 로그로 거짓 PASS가 나지 않도록 매 단계 로그를 비운다.
        Adb @('logcat', '-c') | Out-Null
        $r = AdbShell $step.Cmd
        $seen = Wait-Log $step.Expect 6 @('NSPROBE')
        $injectLog += "$($step.Cmd)`n$($r.Out)`nseen=$seen`n`n"
        Add-Result "VHAL 주입: $($step.Name)" ($(if ($seen) { 'PASS' } else { 'FAIL' })) (($r.Out -split "`r?`n" | Select-Object -First 1))
    }
    Save-Text 'vhal-inject.txt' $injectLog

    Write-Step '시연 모드 E2E (adb 목 신호, 2건)'
    Adb @('logcat', '-c') | Out-Null
    AdbShell "am start -n $Pkg/.drive.DriveActivity --es mode demo --ez reset true --ez autostart true" | Out-Null
    Start-Sleep -Seconds 4
    Take-Screenshot 'demo-0-start.png'
    Send-Signal 'gear' 'D'
    Send-Signal 'odo' '1000.0'
    for ($k = 1; $k -le 2; $k++) {
        Send-Signal 'gear' 'P'
        Send-Signal 'door' 'open'
        Send-Signal 'door' 'closed'
        if ($k -eq 1) { Take-Screenshot 'demo-1-ready.png' }
        Send-Signal 'odo' ("{0:0.0}" -f (1000 + 1.4 * $k))
        AdbShell "am broadcast -a app.nextstop.SIGNAL -p $Pkg --es gear D" | Out-Null
        Start-Sleep -Milliseconds 450
        if ($k -eq 1) { Take-Screenshot 'demo-2-transition.png' }
        Start-Sleep -Seconds 2
        if ($k -eq 1) { Take-Screenshot 'demo-3-next.png' }
    }
    # 플랫폼 주행 제한을 켠 상태의 주행 화면(2줄)도 남긴다.
    AdbShell "am start -n $Pkg/.drive.DriveActivity" | Out-Null
    AdbShell $regOn | Out-Null
    Start-Sleep -Seconds 2
    Take-Screenshot 'demo-4-regulated.png'
    AdbShell $regOff | Out-Null
    $dlog = Get-Log @('NextStop')
    Save-Text 'demo-logcat.txt' $dlog
    $completed = ([regex]::Matches($dlog, 'NS\|EFFECT\|STOP_COMPLETED')).Count
    $navs = ([regex]::Matches($dlog, 'NS\|NAV\|Requested')).Count
    Add-Result '시연 모드: 1건 완료 판정' ($(if ($completed -ge 2) { 'PASS' } else { 'FAIL' })) "STOP_COMPLETED x$completed (기대 2)"
    Add-Result '시연 모드: 다음 배송지 내비 전달' ($(if ($navs -ge 3) { 'PASS' } else { 'FAIL' })) "Requested x$navs (기대 3)"
    $crash = (Adb @('logcat', '-d', '-b', 'crash')).Out
    if ($crash -match $Pkg) { Save-Text 'crash.txt' $crash; Add-Result '앱 크래시' 'FAIL' 'crash.txt 확인' } else { Add-Result '앱 크래시' 'PASS' '없음' }

    Adb @('pull', "/sdcard/Android/data/$Pkg/files/probe-events.jsonl", (Join-Path $OutDir 'probe-events.jsonl')) | Out-Null
}

# ---------------------------------------------------------------- 요약
Write-Step '요약'
$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# 실측 결과 ($Phase, $Stamp)")
[void]$md.AppendLine('')
[void]$md.AppendLine('| 항목 | 결과 | 상세 |')
[void]$md.AppendLine('|---|---|---|')
foreach ($r in $Results) {
    $d = ($r.Detail -replace '\|', '/' -replace "`r?`n", ' ')
    if ($d.Length -gt 220) { $d = $d.Substring(0, 220) + '…' }
    [void]$md.AppendLine("| $($r.Item) | $($r.Status) | $d |")
}
[void]$md.AppendLine('')
[void]$md.AppendLine('파일: ' + ((Get-ChildItem $OutDir | ForEach-Object { $_.Name }) -join ', '))
Save-Text 'SUMMARY.md' $md.ToString()
Write-Host $md.ToString()
Write-Host "결과 폴더: $(Hide-Personal $OutDir)"
