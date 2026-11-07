"""리허설 기록 집계 — 기획서·README의 리허설 숫자를 누구나 같은 방법으로 다시 셀 수 있게 한다.

    python scripts/demo/rehearsal_stats.py            (저장소 맨 위에서)
    python scripts/demo/rehearsal_stats.py --since 20261104

docs/probe-results/*/rehearsal/ 폴더를 모두 읽는다.
- 결과 줄(result.txt)이 있는 회차: C(차량 신호, Virtual Console) x/5 · B(시연 신호, adb) x/5 분포
- 완료 기록: c-app.txt 의 NS|EFFECT|STOP_COMPLETED 줄(차량 신호 경로) — 회차 안에서 같은 index가 두 번 찍히면 한 번으로
- 문 없이 센 완료: 그 줄의 doorCycles=0 (있으면 오탐)
- 신호 지연: result.txt 의 '중앙값 n ms'(회차별 Virtual Console 요청 → 앱 수신 중앙값)
결과 줄이 없는 회차(앱·에뮬레이터·스크립트가 중간에 멈춤)는 따로 센다 — 실패를 빼지 않는다.
"""
import argparse
import glob
import os
import re
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "probe-results")


def stamp(folder):
    m = re.search(r"(\d{8}(?:-\d{4})?)", os.path.basename(folder))
    return m.group(1) if m else ""


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default="", help="YYYYMMDD 또는 YYYYMMDD-HHMM 이후 폴더만(예: 최종 코드 20261107-1526)")
    a = ap.parse_args()
    runs = sorted(d for d in glob.glob(os.path.join(ROOT, "*", "rehearsal")) if stamp(os.path.dirname(d)) >= a.since)
    c_dist, b_dist, medians = {}, {}, []
    no_result, completed, doorless = [], 0, 0
    for r in runs:
        name = os.path.basename(os.path.dirname(r))
        res = os.path.join(r, "result.txt")
        app = os.path.join(r, "c-app.txt")
        if os.path.exists(app):
            seen = set()
            for line in open(app, encoding="utf-8", errors="replace"):
                m = re.search(r"STOP_COMPLETED\|index=(\d+).*?doorCycles=(\d+)", line)
                if m and m.group(1) not in seen:
                    seen.add(m.group(1))
                    completed += 1
                    doorless += m.group(2) == "0"
        if not os.path.exists(res):
            no_result.append(name)
            continue
        t = open(res, encoding="utf-8", errors="replace").read()
        c = re.search(r"C (\d)/5", t)
        b = re.search(r"B (\d)/5", t)
        if c:
            c_dist[int(c.group(1))] = c_dist.get(int(c.group(1)), 0) + 1
        if b:
            b_dist[int(b.group(1))] = b_dist.get(int(b.group(1)), 0) + 1
        md = re.search(r"중앙값 (\d+)ms", t)
        if md:
            medians.append(int(md.group(1)) / 1000)
    with_result = len(runs) - len(no_result)
    print(f"리허설 폴더 {len(runs)}개 · 결과 줄 있음 {with_result} · 결과 줄 없음(중간에 멈춤) {len(no_result)}")
    print("C(차량 신호) 분포:", " · ".join(f"{k}/5 {c_dist[k]}회" for k in sorted(c_dist, reverse=True)))
    print("B(시연 신호) 분포:", " · ".join(f"{k}/5 {b_dist[k]}회" for k in sorted(b_dist, reverse=True)))
    c_total = sum(k * n for k, n in c_dist.items())
    c_runs = sum(c_dist.values())
    if c_runs:
        print(f"C 회수율(결과 줄 있는 회차): {c_total}/{c_runs * 5} = {c_total / (c_runs * 5):.0%}")
    print(f"완료 기록(c-app.txt STOP_COMPLETED, 회차 안 중복 제외) {completed}건 · 그중 문 없이 센 것 {doorless}건")
    if medians:
        print(f"신호 지연 회차 중앙값: 최소 {min(medians):.1f}초 · 최대 {max(medians):.1f}초 · 가운데 {statistics.median(medians):.1f}초 ({len(medians)}회)")
    if no_result:
        print("결과 줄 없는 회차:", ", ".join(no_result))


if __name__ == "__main__":
    main()
