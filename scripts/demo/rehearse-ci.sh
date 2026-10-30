#!/usr/bin/env bash
# 시연 대본(docs/demo/demo-script.md) 리허설을 Pleos 공식 에뮬레이터에서 사람 대신 돌리고, 장면마다 시각을 남긴다.
#   C(본선): 계획 화면 → '운행 시작' 누름 → Virtual Console로 기어·운전석 문·주행거리 → 5건 + 문 없는 정차 1회 → 정산
#   B(예비): 시연 신호 모드에서 scripts/demo/demo.ps1 -Launch -Stops 5 -GapSec 3 (대본의 예비 명령과 같은 흐름, 5건까지)
# 내비가 앱을 덮으면 발표자처럼 앱을 다시 앞으로 부르고, 그 일을 timeline에 '앱으로 복귀'로 남긴다.
#
#   scripts/demo/rehearse-ci.sh <출력 폴더>     (CI emulator 워크플로에서 커밋 메시지에 [rehearsal]일 때)
# 출력: timeline.txt(밀리초 시각 + 장면), c-app.txt·b-app.txt(앱 로그, epoch 시각), *.png(장면 캡처), ui-*.xml,
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
top() { "$adb" shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' | grep -o '[a-z0-9.]*/[A-Za-z0-9.$]*' | head -1; }
shot() { "$adb" exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }
back_to_app() {
  t=$(top)
  case "$t" in app.nextstop/*) ;; *) mark "$1 top=$t → 앱으로 복귀"; "$adb" shell am start -n "$APP" >/dev/null; sleep 2 ;; esac
}

cap_start() {
  F="$TMPD/frames-$1"; mkdir -p "$F"
  ( while [ ! -f "$F/.stop" ]; do "$adb" exec-out screencap -p > "$F/$(now).png" 2>/dev/null; done ) &
  CAP=$!
}
cap_stop() { touch "$F/.stop"; wait "$CAP"; python3 scripts/demo/frames-to-mp4.py "$F" "demo-output/rehearsal-$1.mp4"; }

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
"$adb" install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$adb" forward tcp:8088 tcp:8088 >/dev/null
"$adb" logcat -c
"$adb" logcat -v epoch -s NextStop:I > "$OUT/c-app.txt" &
LOG=$!
cap_start c
mark "C 0:00 계획 화면 띄움"
"$adb" shell am start -n "$APP" --es mode vehicle --ez navi true --ez reset true >/dev/null
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
(cd "$TMPD" && VC_AFTER_STEP="$HOOK" VC_BRIDGE_WAIT=240 timeout 900 node vc-drive.mjs "$OUT/c-vc" \
  gear:D $NEXT $NEXT gear:P gear:D $NEXT $NEXT $NEXT gear:P) > "$OUT/c-vc.log" 2>&1
mark "C VC 끝 exit=$?"
sleep 8; back_to_app "C 정산"; sleep 3; shot c-9-settle
mark "C 정산 화면 top=$(top)"
cap_stop c
kill $LOG 2>/dev/null
mark "C 앱 복귀 횟수=$(grep -c ' C .*앱으로 복귀' "$T")"

# ---------- B: 시연 신호 목업(예비) ----------
car_ok && mark "B 전 car_service 살아 있음" || mark "B 전 car_service 없음(에뮬레이터 시스템 불안정)"
"$adb" logcat -c
"$adb" logcat -v epoch -s NextStop:I > "$OUT/b-app.txt" &
LOG=$!
cap_start b
mark "B 시작: demo.ps1 -Launch -Stops 5 -GapSec 3"
timeout 600 pwsh scripts/demo/demo.ps1 -Launch -Stops 5 -GapSec 3 > "$OUT/b-demo.log" 2>&1
mark "B demo.ps1 끝 exit=$? top=$(top)"
sleep 6; back_to_app "B 정산"; sleep 3; shot b-9-settle
cap_stop b
kill $LOG 2>/dev/null
mark "B 앱 복귀 횟수=$(grep -c ' B .*앱으로 복귀' "$T")"
echo "::notice title=rehearsal::C STOP_COMPLETED x$(grep -c 'STOP_COMPLETED' "$OUT/c-app.txt") · B STOP_COMPLETED x$(grep -c 'STOP_COMPLETED' "$OUT/b-app.txt") · $(grep -c '앱으로 복귀' "$T") returns"
exit 0
