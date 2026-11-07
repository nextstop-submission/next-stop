#!/usr/bin/env bash
# 시연 대본(docs/demo/demo-script.md) 리허설을 Pleos 공식 에뮬레이터에서 사람 대신 돌리고, 장면마다 시각을 남긴다.
#   C(본선): 계획 화면 → '운행 시작' 누름 → Virtual Console로 기어·운전석 문·주행거리 → 5건 + 문 없는 정차 1회 → 정산
#   B(예비): 시연 신호 모드에서 scripts/demo/demo.ps1 -Launch -Stops 5 -GapSec 3 (대본의 예비 명령과 같은 흐름, 5건까지)
# 내비가 앱을 덮으면 발표자처럼 앱을 다시 앞으로 부르고, 그 일을 timeline에 '앱으로 복귀'로 남긴다.
#
#   scripts/demo/rehearse-ci.sh <출력 폴더>     (CI emulator 워크플로에서 커밋 메시지에 [rehearsal]일 때)
# 출력: timeline.txt(밀리초 시각 + 장면), c-app.txt·b-app.txt(앱 로그, epoch 시각), *.png(장면 캡처), ui-*.xml,
#       c-latency.tsv(Virtual Console 요청 시각 ↔ 앱 NS|SIGNAL 수신 시각 짝과 지연), result.txt(회차 요약 한 줄),
#       demo-output/rehearsal-{c,b}.mp4(화면 캡처를 이어 붙인 녹화)
set -u
OUT="${1:?출력 폴더}"
mkdir -p "$OUT" demo-output
OUT="$(cd "$OUT" && pwd)"   # 훅은 다른 폴더에서 돌기 때문에 절대 경로로
command -v ffmpeg >/dev/null || sudo apt-get install -y -qq ffmpeg >/dev/null
TMPD="${RUNNER_TEMP:-/tmp}"
adb="$ANDROID_HOME/platform-tools/adb"
APP=app.nextstop/.drive.DriveActivity
T="$OUT/timeline.txt"
now() { date +%s%3N; }
mark() { echo "$(now) $*" | tee -a "$T"; }
top() { timeout 20 "$adb" shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' | grep -o '[a-z0-9.]*/[A-Za-z0-9.$]*' | head -1; }
shot() { timeout 15 "$adb" exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }
back_to_app() {
  t=$(top)
  case "$t" in app.nextstop/*) ;; *) mark "$1 top=$t → 앱으로 복귀"; "$adb" shell am start -n "$APP" >/dev/null; sleep 2 ;; esac
}

cap_start() {
  F="$TMPD/frames-$1"; rm -rf "$F"; mkdir -p "$F"
  ( while [ ! -f "$F/.stop" ]; do timeout 15 "$adb" exec-out screencap -p > "$F/$(now).png" 2>/dev/null; done ) &
  CAP=$!
}
# 11/7 공개 저장소 2회차(run 42345610411)가 이 단계에서 40분 넘게 멈췄다 — 기기가 굳으면 screencap·wait가 끝나지 않는다.
# → 화면 캡처 1장 15초 제한, 캡처 루프는 30초 안에 안 끝나면 죽이고 넘어간다.
cap_stop() {
  touch "$F/.stop"
  ( sleep 30; kill "$CAP" 2>/dev/null ) & local guard=$!
  wait "$CAP" 2>/dev/null; kill "$guard" 2>/dev/null
  timeout 300 python3 scripts/demo/frames-to-mp4.py "$F" "demo-output/rehearsal-$1.mp4"
}
# 스크립트가 어떻게 끝나든(바깥 timeout 포함) 백그라운드 logcat·캡처가 남아 단계를 붙잡지 않게
cleanup() { touch "$TMPD"/frames-*/.stop 2>/dev/null; jobs -p | xargs -r kill 2>/dev/null; }
trap cleanup EXIT TERM INT

# 화면에서 글자로 버튼을 찾아 누른다(uiautomator). 못 찾으면 1을 돌려준다.
tap_text() {
  "$adb" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$adb" shell cat /sdcard/ui.xml > "$OUT/ui-$2.xml" 2>/dev/null
  xy=$(python3 scripts/demo/ui-find.py "$OUT/ui-$2.xml" "$1")
  [ -n "$xy" ] || return 1
  "$adb" shell input tap $xy
}

# 앱이 맨 앞에 3번 연달아 보일 때까지 기다린다. 부팅 직후 Pleos 내비가 스스로 앞으로 나와 앱을 덮는다(10/30 리허설 1회차).
settle_app() {
  ok=0
  for i in $(seq 1 30); do
    case "$(top)" in app.nextstop/*) ok=$((ok + 1)) ;; *) ok=0; "$adb" shell am start -n "$APP" >/dev/null ;; esac
    [ $ok -ge 3 ] && { mark "$1 앱이 맨 앞(${i}번째 확인)"; return 0; }
    sleep 3
  done
  mark "$1 앱이 맨 앞에 머물지 않음"; return 1
}
# 로그에 운행 시작(첫 목적지 전달)이 찍혔는지
run_started() { grep -q 'NAVIGATE_TO|index=0' "$1"; }
car_ok() { timeout 20 "$adb" shell service check car_service 2>/dev/null | grep -q ': found'; }

# vc-drive.mjs가 단계마다 부르는 훅: 시각·맨 앞 화면을 남기고, 내비가 덮었으면 앱으로 돌아온다(발표자 동작)
HOOK="$TMPD/rh-step.sh"
{
  echo '#!/usr/bin/env bash'
  echo "adb='$adb'; APP='$APP'; T='$T'"
  echo 't=$("$adb" shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | grep -o "[a-z0-9.]*/[A-Za-z0-9.\$]*" | head -1)'
  echo 'echo "$(date +%s%3N) C step $1 top=$t" >> "$T"'
  echo 'case "$t" in app.nextstop/*) ;; *) echo "$(date +%s%3N) C step $1 → 앱으로 복귀" >> "$T"; "$adb" shell am start -n "$APP" >/dev/null; sleep 2 ;; esac'
} > "$HOOK"
chmod +x "$HOOK"

# ---------- C: Virtual Console 실제 신호 ----------
if [ ! -f "$TMPD/vc-drive.mjs" ]; then (cd "$TMPD" && npm i --no-save playwright-core@1.48.2 >/dev/null 2>&1); fi
cp scripts/probe/vc-drive.mjs "$TMPD/"
# C 한 번 돌리기(기기가 멈추면 재부팅 뒤 한 번 더 부른다)
run_c() {
  "$adb" install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
  "$adb" forward tcp:8088 tcp:8088 >/dev/null
  "$adb" logcat -c
  "$adb" logcat -v epoch -s NextStop:I > "$OUT/c-app.txt" &
  LOG=$!
  cap_start c
  mark "C 0:00 계획 화면 띄움"
  # 배송지는 가상 샘플(debug 빌드 전용 옵션 — 앱은 출발점 고정 좌표·샘플을 시연 모드 밖에서 쓰지 않는다)
  "$adb" shell am start -n "$APP" --es mode vehicle --ez navi true --ez reset true --ez sample true >/dev/null
  sleep 5
  settle_app "C 계획"
  shot c-1-plan
  mark "C 0:40 '운행 시작' 누름"
  for try in 1 2; do
    if tap_text "운행 시작" "start-$try"; then mark "C 운행 시작 탭 $try"; else mark "C 운행 시작 버튼 못 찾음 $try"; fi
    sleep 4
    run_started "$OUT/c-app.txt" && break
    settle_app "C 재시도 전"
  done
  run_started "$OUT/c-app.txt" && mark "C 운행 시작 확인(첫 목적지 전달)" || { mark "C 탭으로 시작 안 됨 → autostart 인텐트"; "$adb" shell am start -n "$APP" --ez autostart true >/dev/null; sleep 4; }
  sleep 6; shot c-2-after-start
  mark "C 시작 직후 top=$(top)"
  back_to_app "C 시작"
  sleep 3; shot c-3-app

  NEXT="odo:+2 gear:P door:open door:close gear:D"
  mark "C 1:00 VC 시작: 1건째 → 2건째 → 문 없는 정차 → 3~5건째"
  # 11/4 회차 2번이 900초 제한에 걸려 마지막 집을 못 돌았다(CI 러너가 느리면 단계당 30초 넘게 걸림) → 1800초
  (cd "$TMPD" && VC_AFTER_STEP="$HOOK" VC_BRIDGE_WAIT=240 timeout 1800 node vc-drive.mjs "$OUT/c-vc" \
    gear:D $NEXT $NEXT gear:P gear:D $NEXT $NEXT $NEXT gear:P) > "$OUT/c-vc.log" 2>&1
  mark "C VC 끝 exit=$?"
  # 마지막 신호가 늦게 올 수 있다(11/2: 단계마다 수십 초, 11/5 ci-20261105-2128-e4f1f9d: VC 기어만 약 2분 늦게 도착)
  # → 운행 끝(ALL_DONE)을 최대 240초 기다린다
  for i in $(seq 1 80); do grep -q 'ALL_DONE' "$OUT/c-app.txt" && break; sleep 3; done
  mark "C 운행 끝 기록=$(grep -c 'ALL_DONE' "$OUT/c-app.txt")"
  sleep 5; back_to_app "C 정산"; sleep 3; shot c-9-settle
  mark "C 정산 화면 top=$(top)"
  cap_stop c
  kill $LOG 2>/dev/null
  mark "C 앱 복귀 횟수=$(grep -c ' C .*앱으로 복귀' "$T")"
  LAT=$(python3 scripts/demo/signal-latency.py "$OUT/c-vc.log" "$OUT/c-app.txt" "$OUT/c-latency.tsv" 2>&1 | tail -1)
  mark "C 신호 지연: $LAT"
}


# 이번 C 시도에서 맨 앞 화면을 못 읽은 단계 수(기기 프레임워크 정지 감지)
dead_steps() { awk '/ C 0:00 /{n=0} / C step [0-9]+ top=$/{n++} END{print n+0}' "$T"; }
run_c
# 11/4·11/5 CI에서 VC 화면을 여는 동안 에뮬레이터 프레임워크가 멈춘 회차가 있었다(화면 검정, dumpsys 응답 없음, 앱 로그 끊김,
# 이어서 '앱이 설치돼 있지 않음'). 앱 판정과 무관한 환경 고장이므로 재부팅 뒤 C를 한 번만 다시 돌리고, 1차 기록은 c1-*로 남긴다.
CRETRY=0
if [ "$(dead_steps)" -ge 5 ]; then
  CRETRY=1
  mark "C 1차 기기 정지(무응답 $(dead_steps)단계) → 재부팅 뒤 C 다시"
  for f in c-app.txt c-vc.log c-latency.tsv; do [ -f "$OUT/$f" ] && mv "$OUT/$f" "$OUT/c1-${f#c-}"; done
  [ -d "$OUT/c-vc" ] && mv "$OUT/c-vc" "$OUT/c1-vc"
  timeout 30 "$adb" reboot || true
  sleep 20; timeout 180 "$adb" wait-for-device
  for i in $(seq 1 90); do [ "$(timeout 20 "$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 5; done
  sleep 60
  BOOT=$(timeout 20 "$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  mark "C 재부팅 끝 boot=$BOOT"
  if [ "$BOOT" = "1" ]; then
    run_c
  else
    # 11/7 run 42345610411: 재부팅 뒤 기기가 돌아오지 않았는데 C·B를 이어 돌다 단계가 끝나지 않았다 → 여기서 끝내고 결과를 남긴다
    mark "C 재부팅 실패 → C 다시·B 건너뜀"
    echo "C 0/5 · B 건너뜀 · 기기 정지 뒤 재부팅 실패(환경) · 무응답 단계 $(dead_steps)" | tee "$OUT/result.txt"
    echo "::notice title=rehearsal::$(cat "$OUT/result.txt")"
    exit 0
  fi
fi

# ---------- B: 시연 신호 목업(예비) ----------
car_ok && mark "B 전 car_service 살아 있음" || mark "B 전 car_service 없음(에뮬레이터 시스템 불안정)"
"$adb" logcat -c
"$adb" logcat -v epoch -s NextStop:I > "$OUT/b-app.txt" &
LOG=$!
cap_start b
mark "B 시작: demo.ps1 -Launch -Stops 5 -GapSec 3"
timeout 600 pwsh scripts/demo/demo.ps1 -Launch -Stops 5 -GapSec 3 > "$OUT/b-demo.log" 2>&1
mark "B demo.ps1 끝 exit=$? top=$(top)"
# 마지막 D 뒤에는 주행 중 2줄 화면이다. 정산은 P에서만 열리므로 P를 넣고 찍는다.
sleep 4; "$adb" shell am broadcast -a app.nextstop.SIGNAL -p app.nextstop --es gear P >/dev/null
sleep 2; back_to_app "B 정산"; sleep 3; shot b-9-settle
cap_stop b
kill $LOG 2>/dev/null
mark "B 앱 복귀 횟수=$(grep -c ' B .*앱으로 복귀' "$T")"
C=$(grep -c 'STOP_COMPLETED' "$OUT/c-app.txt"); B=$(grep -c 'STOP_COMPLETED' "$OUT/b-app.txt")
RESTARTS=$(grep -c 'NS|START|' "$OUT/c-app.txt")
CREDIT=$(grep -c 'DOOR_CYCLE_CREDITED' "$OUT/c-app.txt")
VCFAIL=$(grep -cE '^[0-9]+ door:.*sent=\[\]' "$OUT/c-vc.log")
VCSTEPS=$(grep -cE '^[0-9]+ (gear|door|odo):' "$OUT/c-vc.log")
POLL=$(grep -c 'NS|POLL|' "$OUT/c-app.txt")
# 기기 응답 없음(맨 앞 화면을 못 읽음) = 에뮬레이터 프레임워크가 죽은 회차(11/4 0201: 화면 검정, 앱 로그 끊김) → 판정 무효로 표시
DEAD=$(dead_steps)
echo "C ${C}/5 · B ${B}/5 · C 재시도 ${CRETRY}(1이면 1차 기기 정지로 재부팅 뒤 2차) · VC 단계 ${VCSTEPS}/29 · 기기 무응답 단계 ${DEAD} · 앱 시작 ${RESTARTS}회(1이면 재시작 없음) · 보정 인정 ${CREDIT} · 직접 읽기로 찾은 변화 ${POLL} · VC 문 요청 안 나감 ${VCFAIL} · 신호 지연 ${LAT}" | tee "$OUT/result.txt"
echo "::notice title=rehearsal::$(cat "$OUT/result.txt")"
exit 0
