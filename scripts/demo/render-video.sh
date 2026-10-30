#!/usr/bin/env bash
# 시연 영상 예비 경로: 화면 테스트가 남긴 장면(scenes.tsv)에 제목·끝 카드와 자막을 붙여 mp4 하나로 만든다.
#   ./gradlew :app:testDebugUnitTest --tests '*DemoVideoScenesTest*' -Pnextstop.video=true   (장면 → app/build/video)
#   scripts/demo/render-video.sh app/build/video demo-output/nextstop-demo-scenes.mp4
# 필요: ffmpeg, 한글 글꼴(Noto Sans CJK — 경로는 FONT 환경 변수로 바꿀 수 있음). 메타데이터는 비운다(익명).
set -euo pipefail
IN=${1:?scene folder}
OUT=${2:-demo-output/nextstop-demo-scenes.mp4}
if [ -z "${FONT:-}" ]; then
  # 한글이 있는 글꼴만 쓴다(fc-match는 없으면 엉뚱한 글꼴로 대체하므로 한국어 지원 목록에서 고른다)
  FONT=$(fc-list ':lang=ko' file 2>/dev/null | grep -iE 'notosanscjk.*bold|nanum.*bold' | head -1 | cut -d: -f1 || true)
  [ -n "$FONT" ] || FONT=$(fc-list ':lang=ko' file 2>/dev/null | grep -iE 'cjk|nanum|wqy' | head -1 | cut -d: -f1 || true)
fi
[ -f "$FONT" ] || { echo "한글 글꼴을 찾지 못했습니다(FONT=경로)"; exit 1; }
mkdir -p "$(dirname "$OUT")"
TMP=$(mktemp -d)
W=1920; H=1080; FPS=30
CAP=150  # 자막 띠 높이 — 앱 화면을 그 위로 줄여 넣어 자막이 화면 내용을 가리지 않게 한다
VF_SCALE="scale=${W}:$((H - CAP)):force_original_aspect_ratio=decrease,pad=${W}:${H}:(ow-iw)/2:0:color=0x101418"

card() { # 이름 길이(초) 큰글씨 작은글씨
  printf '%s' "$3" > "$TMP/$1-a.txt"; printf '%s' "$4" > "$TMP/$1-b.txt"
  ffmpeg -nostdin -loglevel error -y -f lavfi -i "color=c=0x101418:s=${W}x${H}:r=${FPS}:d=$2" \
    -vf "drawtext=fontfile=$FONT:textfile=$TMP/$1-a.txt:fontcolor=white:fontsize=96:x=(w-tw)/2:y=h/2-120,drawtext=fontfile=$FONT:textfile=$TMP/$1-b.txt:fontcolor=0x9be7a8:fontsize=48:x=(w-tw)/2:y=h/2+40" \
    -pix_fmt yuv420p -c:v libx264 -preset veryfast "$TMP/$1.mp4"
  echo "file '$TMP/$1.mp4'" >> "$TMP/list.txt"
}

: > "$TMP/list.txt"
card 000-title 4 "다음집" "문 닫고 D를 넣으면, 다음 집"
i=0
while IFS=$'\t' read -r file sec caption; do
  [ -n "$file" ] || continue
  i=$((i + 1)); n=$(printf '%03d' "$i")
  printf '%s' "$caption" > "$TMP/$n.txt"
  ffmpeg -nostdin -loglevel error -y -loop 1 -t "$sec" -i "$IN/$file" \
    -vf "$VF_SCALE,drawtext=fontfile=$FONT:textfile=$TMP/$n.txt:fontcolor=white:fontsize=46:x=(w-tw)/2:y=h-$((CAP / 2))-23" \
    -r "$FPS" -pix_fmt yuv420p -c:v libx264 -preset veryfast "$TMP/$n.mp4"
  echo "file '$TMP/$n.mp4'" >> "$TMP/list.txt"
done < "$IN/scenes.tsv"
card 999-end 5 "차가 업무를 알아보면" "사람은 화면을 만질 필요가 없습니다 · Pleos Vehicle SDK · NaviHelper · AAOS"

ffmpeg -nostdin -loglevel error -y -f concat -safe 0 -i "$TMP/list.txt" -c copy -map_metadata -1 -movflags +faststart "$OUT"
rm -rf "$TMP"
echo "영상: $OUT ($(du -h "$OUT" | cut -f1), $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT" | cut -d. -f1)초)"
