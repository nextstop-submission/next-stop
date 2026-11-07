#!/usr/bin/env bash
# 제출용 익명 저장소 만들기: 현재 커밋의 파일만 가져와 '익명 작성자 커밋 1개'짜리 새 이력을 만든다.
# 기존 저장소의 이력·PR·작성자 정보는 옮기지 않는다(원본 저장소는 건드리지 않음).
#
#   scripts/release/anon-export.sh <새 저장소 URL> [출력 폴더]
#   예) scripts/release/anon-export.sh https://github.com/<제출용 조직>/next-stop.git ../next-stop-submission
#
# 순서: 원본 점검(파일) → 새 폴더에 스냅샷 → 익명 커밋 + 태그 → 새 이력 점검(anon-check.sh) → 푸시 명령 안내
# 푸시는 사람이 한다(로그인 필요). --push 를 세 번째 인자로 주면 점검 통과 시 바로 푸시한다.
set -euo pipefail
NEW_REMOTE="${1:?새 저장소 URL이 필요합니다}"
OUT="${2:-../next-stop-submission}"
PUSH="${3:-}"
SRC="$(git rev-parse --show-toplevel)"
NAME="next-stop-dev"
EMAIL="dev@next-stop.invalid"
TAG="submission-$(date +%Y%m%d)"

cd "$SRC"
if [ -n "$(git status --porcelain)" ]; then
  echo "작업 트리에 커밋하지 않은 변경이 있습니다. 커밋하거나 되돌린 뒤 다시 실행하세요."; exit 1
fi
if [ -e "$OUT" ]; then echo "출력 폴더가 이미 있습니다: $OUT (다른 경로를 주세요)"; exit 1; fi

echo "== 1. 스냅샷 ($(git rev-parse --short HEAD)) → $OUT"
mkdir -p "$OUT"
# 추적 중인 파일만(로컬 설정·빌드 산출물·.probe-cache 등 제외)
git archive --format=tar HEAD | tar -x -C "$OUT"
cd "$OUT"

echo "== 2. 익명 이력 만들기"
git init -q
git symbolic-ref HEAD refs/heads/main
git config user.name "$NAME"
git config user.email "$EMAIL"
git add -A
GIT_AUTHOR_NAME="$NAME" GIT_AUTHOR_EMAIL="$EMAIL" GIT_COMMITTER_NAME="$NAME" GIT_COMMITTER_EMAIL="$EMAIL" \
  git commit -q -m "다음집 — 공모전 제출본"
git tag -a "$TAG" -m "공모전 제출본"
git remote add origin "$NEW_REMOTE"
git log --format='%h %an <%ae> | %cn <%ce> | %s'

echo "== 3. 새 이력 점검"
if "$SRC/scripts/release/anon-check.sh" "$PWD"; then
  echo
  if [ "$PUSH" = "--push" ]; then
    git push -u origin main --follow-tags
  else
    echo "점검 통과. 로그인된 터미널에서 다음을 실행하세요:"
    echo "  cd \"$OUT\" && git push -u origin main --follow-tags"
  fi
else
  echo "점검에서 확인 필요 항목이 나왔습니다. 원본에서 고친 뒤 이 폴더를 지우고 다시 실행하세요: $OUT"
  exit 1
fi
