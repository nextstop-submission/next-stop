#!/usr/bin/env python3
"""Virtual Console 요청 시각 ↔ 앱 NS|SIGNAL 수신 시각을 짝지어 신호 지연 분포를 낸다(심사 지적 C3).

  python3 scripts/demo/signal-latency.py <c-vc.log> <c-app.txt> <출력 latency.tsv>

- c-vc.log: scripts/probe/vc-drive.mjs 출력. 단계마다 `N gear:D: at=<epoch ms> ... sent=[...]`.
- c-app.txt: `adb logcat -v epoch -s NextStop:I`. `NS|SIGNAL|GearChanged(gear=DRIVE, ...)` 줄의 맨 앞 epoch 초.
- 같은 종류(기어 값·문 열림/닫힘)의 앱 수신 중, 요청 2초 전(호스트·기기 시계 차 여유)부터 180초 안의 가장 이른 것을 짝으로 본다.
  요청이 실제로 나가지 않은 단계(sent=[])는 뺀다. 기기 시계와 호스트 시계는 에뮬레이터라 같다고 보지만 수백 ms 오차는 남을 수 있다.
마지막 줄에 요약(짝 n/요청 m, 중앙값, 최대)을 찍는다.
"""
import re
import statistics
import sys

vc_path, app_path, out_path = sys.argv[1:4]
want = {
    ("gear", "D"): "GearChanged(gear=DRIVE", ("gear", "P"): "GearChanged(gear=PARK",
    ("door", "open"): "DoorChanged(open=true", ("door", "close"): "DoorChanged(open=false",
}
reqs = []
for line in open(vc_path, encoding="utf-8", errors="replace"):
    m = re.match(r"^(\d+) (gear|door):(\w+): at=(\d+) .*sent=\[([^\]]*)\]", line)
    if m and m.group(5).strip():
        reqs.append((int(m.group(1)), m.group(2), m.group(3), int(m.group(4))))
recv = []
for line in open(app_path, encoding="utf-8", errors="replace"):
    m = re.match(r"^\s*(\d+\.\d+)\s.*NS\|SIGNAL\|(.*)$", line)
    if m:
        recv.append((int(float(m.group(1)) * 1000), m.group(2)))
used = set()
rows, lat = [], []
for step, what, val, at in reqs:
    key = want.get((what, val))
    hit = None
    for i, (t, ev) in enumerate(recv):
        if i in used or key is None or not ev.startswith(key):
            continue
        if at - 2000 <= t <= at + 180_000:
            hit = (i, t)
            break
    if hit:
        used.add(hit[0])
        lat.append(hit[1] - at)
        rows.append(f"{step}\t{what}:{val}\t{at}\t{hit[1]}\t{hit[1] - at}")
    else:
        rows.append(f"{step}\t{what}:{val}\t{at}\t-\t미수신")
with open(out_path, "w", encoding="utf-8") as f:
    f.write("단계\t요청\tVC 요청(ms)\t앱 수신(ms)\t지연(ms)\n" + "\n".join(rows) + "\n")
    if lat:
        summary = f"짝 {len(lat)}/{len(reqs)} · 중앙값 {int(statistics.median(lat))}ms · 최대 {max(lat)}ms · 최소 {min(lat)}ms"
    else:
        summary = f"짝 0/{len(reqs)}"
    f.write("# " + summary + "\n")
print(summary)
