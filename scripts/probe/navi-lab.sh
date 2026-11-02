#!/usr/bin/env bash
# 내비 실험(실측) 8회차 — guest 그래픽(커밋 메시지 [gpu-guest])
#  7회차(ci-20261102-1407): '내비에서 찾기' → 검색 결과 목록 → '목적지로' → onRouteStarted(서울역 KTX)까지 됐으나
#   onDestinationChanged가 오지 않아 앱이 목적지를 묻지 않았고, 경로 시작이 36초 늦게 와 그사이 ② 시작의 실험 도구 cancel이 경로를 지웠다.
#   → 앱: 대기 중이면 onRouteStarted에서도 목적지를 묻는다(RunStore), cancelRoute마다 부른 쪽 로그(NS|NAV|cancelRoute by=).
#   → 이 스크립트: ①에서 배송지가 들어오거나 90초가 지날 때까지 기다린 뒤 넘어간다. 실험 도구의 cancel은 표시를 남긴다.
#   → ④ 위치가 안 움직였다: NMEA 외에 geo fix(경도 먼저), Android 위치(dumpsys location), Virtual Console 기능 목록·속도를 함께 남긴다.
#  6회차(guest): 내비가 NaviHelper 요청에 한 번도 답하지 않았다(5b와 같은 설정 — CI 내비 기동이 회차마다 다르다).
#  → 3분 대기 뒤에도 내비가 '상태' 요청에 답할 때까지 다시 연결하며 최대 6분 기다린 다음 진행한다.
#  5b회차(guest): 내비가 3분 안에 준비를 끝냈다('어디로 갈까요?'). 경로 요청 → onRouteStarted(이름·1.9km·8분)·ROUTE_DRIVE,
#  내비 목적지가 바뀌자 앱이 그 목적지를 배송지로 받았다(NS|PLACE|Added). 남은 것:
#  ① 앱의 '내비에서 찾기'(SearchPlace)가 검색 결과를 띄우는지 — 3초 간격 캡처, 내비 화면 dump 재시도
#  ② '내비 열어 고르기' → 내비 검색창 누르기 → 영문 입력 → 결과 → 목적지 지정 → 앱으로 돌아와 배송지 추가(NS|PLACE|Added)
#  ③ 앱이 받은 뒤 경로를 취소하는지(5b: 취소 뒤에도 ROUTE_GUIDING이 남음) — 상태를 다시 묻는다
#  ④ 도착 이벤트: geo fix의 NMEA는 내비가 체크섬 오류로 버렸다 → 체크섬을 맞춘 NMEA(geo nmea)로 위치를 옮긴다
#   scripts/probe/navi-lab.sh <출력 폴더>      (CI emulator 워크플로에서 커밋 메시지에 [navi-lab]일 때)
set -u
OUT="${1:?출력 폴더}"; mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
adb="$ANDROID_HOME/platform-tools/adb"
NAVI=ai.umos.maps.android.navigation.app
T="$OUT/timeline.txt"
mark() { echo "$(date +%s%3N) $*" | tee -a "$T"; }
shot() { "$adb" exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }
lab() { "$adb" shell am broadcast -a app.nextstop.LAB "$@" >/dev/null; }   # 값에 공백을 넣지 않는다(adb shell이 다시 나눔)
# 맨 앞 화면: 차량 이미지는 화면 영역이 여러 개라 첫 줄만 보면 틀린다(6회차: 캡처는 주행 화면인데 실측 화면으로 적힘) → 앞에 있는 것 모두
top() { "$adb" shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity:' | grep -o '[a-z0-9.]*/[A-Za-z0-9.$]*' | sort -u | tr '\n' ' '; }
npid() { "$adb" shell pidof "$NAVI" 2>/dev/null | tr -d '\r'; }
drive() { "$adb" shell am start -n app.nextstop/.drive.DriveActivity "$@" >/dev/null; }
lab_ui() { "$adb" shell am start -n app.nextstop/.probe.ProbeActivity --es lab navi >/dev/null; }
# 화면 dump(내비 화면에서는 죽을 수 있다 — 실패하면 빈 파일). 글자 있는 요소 목록을 <이름>.txt로
dump() {
  "$adb" shell rm -f /sdcard/ns-ui.xml >/dev/null 2>&1
  timeout 25 "$adb" shell uiautomator dump /sdcard/ns-ui.xml >/dev/null 2>&1
  "$adb" pull /sdcard/ns-ui.xml "$OUT/$1.xml" >/dev/null 2>&1 || : > "$OUT/$1.xml"
  python3 "$HERE/ui-nodes.py" list "$OUT/$1.xml" > "$OUT/$1.txt" 2>/dev/null
  mark "dump $1: 요소 $(wc -l < "$OUT/$1.txt")개"
}
added() { grep -c 'NS|PLACE|\(Added\|Merged\)' "$OUT/lab-log.txt"; }
SEC=43200
nmea_to() {   # 체크섬 맞춘 NMEA로 위치 한 점
  SEC=$((SEC + 1))
  python3 "$HERE/nmea-fix.py" "$1" "$2" "$SEC" | while read -r s; do "$adb" emu geo nmea "$s" >/dev/null 2>&1; done
}
BTN="안내 시작,목적지 설정,목적지로,경로 안내,출발,길안내"

mark "그래픽: ${GPU_MODE:-?} / $("$adb" shell dumpsys SurfaceFlinger | grep -m1 -i 'GLES:' | tr -d '\r' | cut -c1-160)"
"$adb" install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$adb" logcat -G 16M >/dev/null 2>&1; "$adb" logcat -c
"$adb" logcat -v epoch -s NSPROBE:I NextStop:I > "$OUT/lab-log.txt" &
LOG=$!
mark "내비 준비 대기 180초"
for t in 60 120 180; do sleep 60; mark "${t}초: 내비 PID $(npid) / 맨 앞 $(top)"; done
shot 0-navi-ready
ready=0
for i in $(seq 1 12); do
  lab_ui; sleep 3; lab --es cmd reconnect; sleep 6; lab --es cmd state; lab --es cmd loc; sleep 5
  if grep -q 'onRouteStateInfo' "$OUT/lab-log.txt"; then ready=1; break; fi
  "$adb" shell am start -n "$NAVI/.MainActivity" >/dev/null 2>&1; sleep 15
done
mark "내비 응답: $([ $ready = 1 ] && echo "있음(${i}회째)" || echo '없음 — 이 회차의 나머지 결과는 참고만')"
shot 0-navi-answer

# ---------- ① 앱의 '내비에서 찾기'
mark "① 주행 화면: 목록 비우고 '내비에서 찾기'(서울역)"
drive --es mode vehicle --ez reset true --ez clear_stops true; sleep 8
drive --es place u:%EC%84%9C%EC%9A%B8%EC%97%AD
for i in 1 2 3 4 5 6 7 8; do sleep 3; shot "1-search-$((i * 3))s"; [ $i = 2 ] && mark "검색 6초: 맨 앞 $(top)"; done
mark "검색 24초: 맨 앞 $(top)"
dump 1-search-ui
# 결과 목록이 보이면 '서울역'이 든 첫 요소를 누르고, 목적지 지정 버튼을 찾는다
xy=$(python3 "$HERE/ui-nodes.py" tap-contains "$OUT/1-search-ui.xml" 서울역 0)
if [ -n "$xy" ]; then
  mark "검색 결과 누르기 $xy"; "$adb" shell input tap $xy; sleep 5; shot 1-result; dump 1-result-ui
  b=$(python3 "$HERE/ui-nodes.py" tap-any "$OUT/1-result-ui.xml" "$BTN")
  [ -n "$b" ] && { mark "목적지 버튼 $b"; set -- $b; "$adb" shell input tap "$1" "$2"; sleep 8; shot 1-dest-set; }
fi
# 앱은 뒤에 둔 채로 기다린다 — 경로 시작(CI에서 30초 넘게 걸림) → 앱이 목적지를 물어 배송지로 받고 경로를 취소하는지
for i in $(seq 1 30); do
  [ "$(added)" -gt 0 ] && break
  sleep 3
done
mark "① 경로 시작 $(grep -c 'NS|NAV|RouteStarted' "$OUT/lab-log.txt")회 / 앱 질문 $(grep -c 'NS|PLACE|route-started' "$OUT/lab-log.txt")회 / 취소 $(grep -o 'NS|NAV|cancelRoute by=[^ ]*' "$OUT/lab-log.txt" | tail -1)"
drive; sleep 6; shot 1-back; dump 1-plan-ui
mark "① 끝: 배송지 추가 $(added)건"

# ---------- ② '내비 열어 고르기' → 내비 검색창 → 영문 입력
lab_ui; sleep 3; mark "② 전: 실험 도구가 경로 취소(cmd cancel)"; lab --es cmd cancel; sleep 3
mark "② '내비 열어 고르기'"
drive --ez pick true; sleep 8; shot 2-navi; mark "맨 앞 $(top)"
dump 2-navi-ui
box=$(python3 "$HERE/ui-nodes.py" tap-contains "$OUT/2-navi-ui.xml" 어디로 0)
[ -n "$box" ] || box="1073 1110"     # 5b 화면 캡처의 '어디로 갈까요?' 위치(2560×1440)
mark "검색창 누르기 $box"; "$adb" shell input tap $box; sleep 4; shot 2-searchbox; dump 2-searchbox-ui
"$adb" shell input text seoulstation; sleep 2; "$adb" shell input keyevent 66; sleep 10; shot 2-typed; dump 2-typed-ui
xy=$(python3 "$HERE/ui-nodes.py" tap-contains "$OUT/2-typed-ui.xml" 서울역 0)
[ -n "$xy" ] || xy=$(python3 "$HERE/ui-nodes.py" tap-contains "$OUT/2-typed-ui.xml" 역 0)
if [ -n "$xy" ]; then
  mark "결과 누르기 $xy"; "$adb" shell input tap $xy; sleep 5; shot 2-result; dump 2-result-ui
  b=$(python3 "$HERE/ui-nodes.py" tap-any "$OUT/2-result-ui.xml" "$BTN")
  [ -n "$b" ] && { mark "목적지 버튼 $b"; set -- $b; "$adb" shell input tap "$1" "$2"; sleep 8; shot 2-dest-set; }
fi
drive; sleep 8; shot 2-back
mark "② 끝: 배송지 추가 $(added)건"
dump 2-plan-ui

# ---------- ③ 앱이 받은 뒤 경로가 취소됐는가
lab_ui; sleep 4; lab --es cmd state; lab --es cmd dest; sleep 4
mark "③ 상태: $(grep -o 'naviMode=[A-Z_]*' "$OUT/lab-log.txt" | tail -1)"

# ---------- ④ 경로 → 체크섬 맞춘 NMEA로 이동 → 도착 이벤트
lat0=37.412721; lng0=127.094681
lat1=$(python3 -c "print(f'{$lat0+0.0036:.6f}')")
osloc() { "$adb" shell dumpsys location 2>/dev/null | grep -iE 'last location|Location\[' | head -3 | tr -s ' ' | cut -c1-160 | tr '\n' ' '; }
mark "④ 전 Android 위치: $(osloc)"
# Virtual Console 기능 목록(48개 · 7도메인)에 위치·GNSS가 있는지 — 있으면 다음 회차에 그 길로 옮긴다
"$adb" forward tcp:8088 tcp:8088 >/dev/null 2>&1
curl -s -m 10 http://127.0.0.1:8088/vehicle/catalog > "$OUT/vc-catalog.json" 2>/dev/null
mark "VC 기능 목록 $(wc -c < "$OUT/vc-catalog.json")바이트 · 위치 관련 $(grep -oiE '"[^"]*(locat|gnss|gps|latit|longit|position)[^"]*"' "$OUT/vc-catalog.json" | sort -u | head -8 | tr '\n' ' ')"
for k in 1 2 3; do nmea_to "$lat0" "$lng0"; "$adb" emu geo fix "$lng0" "$lat0" >/dev/null 2>&1; sleep 1; done
mark "④ geo fix·NMEA 뒤 Android 위치: $(osloc)"
lab --es cmd loc; sleep 3
mark "④ NMEA 시작점 뒤 위치: $(grep -o 'onCurrentLocationInfo|at=[-0-9.,]*' "$OUT/lab-log.txt" | tail -1)"
lab --es cmd route --es lat "$lat1" --es lng "$lng0" --es name 실측도착지
sleep 12; shot 4-route
for i in $(seq 1 30); do
  la=$(python3 -c "print(f'{$lat0+0.0036*$i/30:.6f}')")
  nmea_to "$la" "$lng0"; "$adb" emu geo fix "$lng0" "$la" >/dev/null 2>&1; sleep 1
  [ $((i % 10)) = 0 ] && { lab --es cmd loc; shot "4-move-$i"; }
done
for k in $(seq 1 10); do nmea_to "$lat1" "$lng0"; sleep 1; done
sleep 5; shot 4-arrived
lab --es cmd loc; lab --es cmd state; lab --es cmd dest; sleep 4
mark "④ 끝: 위치 $(grep -o 'onCurrentLocationInfo|at=[-0-9.,]*' "$OUT/lab-log.txt" | tail -1) / Android $(osloc) / 도착 이벤트 $(grep -c 'onDestinationArrived' "$OUT/lab-log.txt")건"
# 내비가 Android 위치가 아니라 차량 속도로 위치를 추정하는지: Virtual Console로 D + 속도 40km/h 30초
curl -s -m 5 -X PUT 'http://127.0.0.1:8088/vehicle/gear?gear=Drive' >/dev/null
for s in $(seq 1 10); do curl -s -m 5 -X PUT 'http://127.0.0.1:8088/vehicle/controls?speed=40&accelerator=30&brake=0' >/dev/null; sleep 3; done
lab --es cmd loc; sleep 3
mark "④b VC 속도 40 30초 뒤: 위치 $(grep -o 'onCurrentLocationInfo|at=[-0-9.,]*' "$OUT/lab-log.txt" | tail -1) / VC $(curl -s -m 5 http://127.0.0.1:8088/vehicle/state | cut -c1-200) / 도착 이벤트 $(grep -c 'onDestinationArrived' "$OUT/lab-log.txt")건"
curl -s -m 5 -X PUT 'http://127.0.0.1:8088/vehicle/controls?speed=0&accelerator=0&brake=50' >/dev/null
curl -s -m 5 -X PUT 'http://127.0.0.1:8088/vehicle/gear?gear=Park' >/dev/null
lab --es cmd cancel; sleep 3; lab --es cmd state; sleep 3

"$adb" logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null
mark "끝"
kill $LOG 2>/dev/null
echo "::notice title=navi-lab::gpu ${GPU_MODE:-?} · added $(added) · $(grep -o 'lab.event|on[A-Za-z]*' "$OUT/lab-log.txt" | sort | uniq -c | sort -rn | head -12 | tr '\n' ' ')"
exit 0
