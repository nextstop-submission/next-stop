"""화면 캡처(파일 이름 = 밀리초 시각)를 찍힌 간격 그대로 이어 붙여 mp4로 만든다. 메타데이터는 비운다(익명).

    python3 scripts/demo/frames-to-mp4.py <캡처 폴더> <출력.mp4>
"""
import os
import subprocess
import sys

src, out = sys.argv[1], sys.argv[2]
frames = sorted(
    f for f in os.listdir(src)
    if f.endswith(".png") and f[:-4].isdigit() and os.path.getsize(os.path.join(src, f)) > 1000
)
if not frames:
    sys.exit("캡처가 없습니다")
listing = os.path.join(src, "list.txt")
with open(listing, "w") as f:
    for a, b in zip(frames, frames[1:] + [None]):
        dur = (int(b[:-4]) - int(a[:-4])) / 1000 if b else 1.0
        f.write(f"file '{os.path.join(src, a)}'\nduration {dur:.3f}\n")
    f.write(f"file '{os.path.join(src, frames[-1])}'\n")
os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
subprocess.run(
    ["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", listing,
     "-vf", "scale=1280:-2,fps=10", "-pix_fmt", "yuv420p", "-c:v", "libx264", "-crf", "28",
     "-map_metadata", "-1", "-movflags", "+faststart", out],
    check=True,
)
first, last = int(frames[0][:-4]), int(frames[-1][:-4])
print(f"{out}: {len(frames)}장, {(last - first) / 1000:.0f}초")
