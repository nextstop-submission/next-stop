#!/usr/bin/env bash
# 익명 심사 최종 점검. 저장소(파일 + 전체 이력 + APK + 이미지)에 개인 식별 정보가 남았는지 검사한다.
#
#   scripts/release/anon-check.sh [저장소 경로]          (기본: 현재 저장소)
#
# 개인 정보 패턴(실명·학교·학번·계정명·이메일·전화번호 등)은 저장소 밖 파일에서만 읽는다.
#   기본 위치: ~/.nextstop-anon-deny.txt   (한 줄에 정규식 하나, 대소문자 무시. 이 파일은 절대 커밋하지 않는다)
#   다른 위치: NEXTSTOP_ANON_DENY=/경로/파일 scripts/release/anon-check.sh
#
# 종료 코드: 0 = 문제 없음, 1 = 확인 필요 항목 있음, 2 = 실행 오류
# Git Bash(Windows)·macOS·Linux에서 동작. 필요 도구: git, grep, unzip(선택: Android SDK build-tools의 apksigner).
set -u
REPO="${1:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -n "$REPO" ] && [ -d "$REPO/.git" ] || { echo "저장소를 찾을 수 없습니다: $REPO"; exit 2; }
cd "$REPO" || exit 2

DENY="${NEXTSTOP_ANON_DENY:-$HOME/.nextstop-anon-deny.txt}"
ALLOW_IDENTITY_RE='^(next-stop-dev <dev@next-stop\.invalid>)$'
problems=0
warn() { echo "  [확인 필요] $*"; problems=$((problems + 1)); }
ok() { echo "  [통과] $*"; }
section() { echo; echo "== $*"; }

if [ ! -s "$DENY" ]; then
  echo "개인 정보 패턴 파일이 없습니다: $DENY"
  echo "예) 실명, 영문 이름, 학교명(국·영), 학번, GitHub 계정명, 이메일, 전화번호를 한 줄에 하나씩 적어 만든 뒤 다시 실행하세요."
  exit 2
fi
case "$(cd "$(dirname "$DENY")" && pwd)/" in
  "$REPO"/*) echo "패턴 파일이 저장소 안에 있습니다. 저장소 밖으로 옮기세요: $DENY"; exit 2 ;;
esac
# 빈 줄·주석 제거
PATTERNS="$(mktemp)"; trap 'rm -f "$PATTERNS"' EXIT
grep -v -E '^\s*(#|$)' "$DENY" > "$PATTERNS"

section "1. 커밋·태그 작성자 (전체 이력)"
ids="$(git log --all --format='%an <%ae>%n%cn <%ce>' | sort -u)"
bad_ids="$(echo "$ids" | grep -v -E "$ALLOW_IDENTITY_RE" || true)"
if [ -n "$bad_ids" ]; then warn "익명 작성자가 아닌 신원:"; echo "$bad_ids" | sed 's/^/      /'; else ok "모든 커밋 작성자·커미터가 next-stop-dev"; fi
tags="$(git for-each-ref refs/tags --format='%(refname:short) %(taggername) %(taggeremail)')"
if [ -n "$tags" ]; then
  bad_tags="$(echo "$tags" | awk 'NF>1' | grep -v 'next-stop-dev <dev@next-stop.invalid>' || true)"
  [ -n "$bad_tags" ] && { warn "익명이 아닌 태그 작성자:"; echo "$bad_tags" | sed 's/^/      /'; } || ok "태그 작성자 익명(또는 가벼운 태그)"
else ok "태그 없음"; fi

section "2. 커밋 메시지"
msg_hits="$(git log --all --format='%H %B' | grep -i -E -f "$PATTERNS" || true)"
[ -n "$msg_hits" ] && { warn "커밋 메시지에 개인 정보 패턴:"; echo "$msg_hits" | cut -c1-160 | sed 's/^/      /'; } || ok "커밋 메시지에 개인 정보 패턴 없음"
trailer_hits="$(git log --all --format='%H %B' | grep -E 'Claude-Session:|claude\.ai/code/session' || true)"
[ -n "$trailer_hits" ] && warn "세션 링크 트레일러가 남아 있음(계정과 연결될 수 있음) — anon-export.sh로 새 이력을 만드세요" || ok "세션 링크 없음"

section "3. 추적 중인 파일 내용·경로"
file_hits="$(git grep -I -n -i -E -f "$PATTERNS" -- . 2>/dev/null || true)"
[ -n "$file_hits" ] && { warn "파일 내용에 개인 정보 패턴:"; echo "$file_hits" | cut -c1-200 | sed 's/^/      /'; } || ok "텍스트 파일에 개인 정보 패턴 없음"
path_hits="$(git ls-files | grep -i -E -f "$PATTERNS" || true)"
[ -n "$path_hits" ] && { warn "파일 이름·경로에 개인 정보 패턴:"; echo "$path_hits" | sed 's/^/      /'; } || ok "파일 이름에 개인 정보 패턴 없음"
home_hits="$(git grep -I -n -E '([A-Za-z]:\\\\Users\\\\[^<\\]+|/Users/[a-zA-Z0-9._-]+|/home/[a-z][a-z0-9._-]+/)' -- . ':!scripts/release/*' 2>/dev/null | grep -v -E '/home/(user|runner)/|<user' || true)"
[ -n "$home_hits" ] && { warn "사용자 홈 경로가 남아 있음:"; echo "$home_hits" | cut -c1-200 | sed 's/^/      /'; } || ok "사용자 홈 경로 없음"

section "4. 이력 전체(지워진 파일 포함)"
hist_hits=""
for c in $(git rev-list --all); do
  h="$(git grep -I -l -i -E -f "$PATTERNS" "$c" -- . 2>/dev/null | head -3)"
  [ -n "$h" ] && hist_hits="$hist_hits$h"$'\n'
done
[ -n "$hist_hits" ] && { warn "과거 커밋의 파일에 개인 정보 패턴(이력 재작성 필요):"; echo "$hist_hits" | sort -u | head -20 | sed 's/^/      /'; } || ok "과거 이력에도 없음"

section "5. APK"
apks="$(git ls-files '*.apk')"
if [ -z "$apks" ]; then warn "커밋된 APK가 없음(apk/NextStop_다음집_release.apk 필요)"; fi
for apk in $apks; do
  if command -v unzip >/dev/null; then
    tmp="$(mktemp -d)"; unzip -q -o "$apk" -d "$tmp" 2>/dev/null
    a_hits="$(grep -r -a -l -i -E -f "$PATTERNS" "$tmp" 2>/dev/null | sed "s#$tmp/##" || true)"
    [ -n "$a_hits" ] && { warn "$apk 내부 파일에 개인 정보 패턴:"; echo "$a_hits" | sed 's/^/      /'; } || ok "$apk 내부에 개인 정보 패턴 없음"
    p_hits="$(grep -r -a -o -E '([A-Za-z]:\\\\Users\\\\[A-Za-z0-9._-]+|/Users/[A-Za-z0-9._-]+)' "$tmp" 2>/dev/null | sort -u | head -5 || true)"
    [ -n "$p_hits" ] && { warn "$apk 에 빌드 PC 사용자 경로:"; echo "$p_hits" | sed 's/^/      /'; } || ok "$apk 에 빌드 PC 경로 없음"
    rm -rf "$tmp"
  else warn "unzip이 없어 APK 내부를 검사하지 못함"; fi
  # minSdk 33 APK는 v2/v3 서명만 있어 keytool로는 못 읽는다 → apksigner(Android SDK build-tools) 사용
  signer="$(command -v apksigner 2>/dev/null || true)"
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [ -n "$sdk" ] || continue
    for c in "$sdk"/build-tools/*/apksigner "$sdk"/build-tools/*/apksigner.bat; do [ -e "$c" ] && signer="$c"; done
  done
  if [ -n "$signer" ]; then
    dn="$("$signer" verify --print-certs "$apk" 2>/dev/null | grep -m1 'certificate DN' || true)"
    if [ -z "$dn" ]; then warn "$apk 서명 확인 실패($signer verify)"
    elif echo "$dn" | grep -q -i -E -f "$PATTERNS"; then warn "$apk 서명 인증서에 개인 정보: $dn"
    else ok "$apk 서명 인증서: ${dn#*DN: }"; fi
  else warn "apksigner를 찾지 못해 서명 인증서를 확인하지 못함(Android SDK build-tools 설치 후 재실행)"; fi
done

section "6. 이미지 메타데이터(PNG 텍스트 청크·EXIF)"
imgs="$(git ls-files '*.png' '*.jpg' '*.jpeg' '*.webp')"
meta_hits=""
for f in $imgs; do
  if grep -q -a -E 'tEXt|iTXt|zTXt|eXIf|Exif' "$f"; then meta_hits="$meta_hits$f"$'\n'; fi
done
[ -n "$meta_hits" ] && { warn "메타데이터 청크가 있는 이미지(내용 확인·제거):"; echo "$meta_hits" | sed '/^$/d; s/^/      /'; } || ok "이미지 메타데이터 청크 없음 (${imgs:+$(echo "$imgs" | wc -l | tr -d ' ')개 검사})"
echo "  [사람 확인] 스크린샷 화면 안에 계정·이름·알림이 찍혔는지는 눈으로 확인: docs/screens/, docs/probe-results/*/*.png"

section "7. 영상 메타데이터"
vids="$(git ls-files '*.mp4' '*.mov' '*.m4v' '*.webm')"
if [ -z "$vids" ]; then ok "영상 없음"
elif ! command -v ffprobe >/dev/null; then warn "ffprobe가 없어 영상 메타데이터를 확인하지 못함(ffmpeg 설치 후 재실행)"
else
  # 인코더가 붙이는 기술 정보만 허용한다. 촬영 시각·기기 정보·제목·작성자·위치는 지운다(ffmpeg -map_metadata -1)
  vid_hits=""
  for f in $vids; do
    t="$(ffprobe -v error -show_entries format_tags:stream_tags -of default=nw=1 "$f" 2>/dev/null \
      | sed -n 's/^TAG:\([^=]*\)=.*/\1/p' | grep -v -i -x -E 'major_brand|minor_version|compatible_brands|encoder|language|handler_name|vendor_id' | sort -u | tr '\n' ' ')"
    [ -n "$t" ] && vid_hits="$vid_hits$f: $t"$'\n'
  done
  [ -n "$vid_hits" ] && { warn "메타데이터가 남은 영상(지운 뒤 다시 커밋):"; echo "$vid_hits" | sed '/^$/d; s/^/      /'; } || ok "영상 메타데이터 없음 ($(echo "$vids" | wc -l | tr -d ' ')개 검사)"
fi
echo "  [사람 확인] 영상 화면 안의 계정·이름·알림은 눈으로 확인: docs/demo/video/, docs/probe-results/*/*.mp4"

section "8. 원격 저장소"
remote="$(git remote get-url origin 2>/dev/null || true)"
if [ -n "$remote" ] && echo "$remote" | grep -q -i -E -f "$PATTERNS"; then warn "origin 주소에 개인 계정명: $remote (제출용 익명 계정/조직 저장소로 옮길 것)"
else ok "origin: ${remote:-없음}"; fi

echo
if [ "$problems" -eq 0 ]; then echo "결과: 문제 없음"; exit 0; else echo "결과: 확인 필요 ${problems}건"; exit 1; fi
