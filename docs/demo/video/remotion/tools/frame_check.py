# 끊김 검사: 1초마다 '앞 프레임과 다른 화면' 수를 센다. 움직이는 실사 구간이 출력 fps에 가깝고 고르게 나오면 끊김이 없다.
# python -I frame_check.py <영상.mp4> <ffmpeg> [문턱=0.6]
# 앞 프레임과의 평균 밝기 차(0~255)가 문턱보다 크면 '다른 화면'. 정지 화면(글자·앱 화면)은 원래 0에 가깝다.
import sys, subprocess, numpy as np

src, ffmpeg = sys.argv[1:3]
th = float(sys.argv[3]) if len(sys.argv) > 3 else 0.6
probe = subprocess.run([ffmpeg, '-i', src], capture_output=True, text=True, errors='ignore').stderr
fps = float(probe.split(' fps')[0].split(',')[-1])
w, h = 135, 240
raw = subprocess.run([ffmpeg, '-v', 'error', '-i', src, '-vf', f'scale={w}:{h}', '-f', 'rawvideo', '-pix_fmt', 'gray', '-'], capture_output=True).stdout
a = np.frombuffer(raw, np.uint8).reshape(-1, h, w).astype(np.int16)
d = np.abs(np.diff(a, axis=0)).mean(axis=(1, 2))
moving = np.concatenate([[True], d > th])
per = int(round(fps))
print(f'fps {fps:g}, 프레임 {len(a)}')
print('초  다른화면  반복(같은 화면이 2장 이상 이어진 수)')
for s in range(0, len(a) // per):
    seg = moving[s * per:(s + 1) * per]
    rep = int(((~seg[1:]) & seg[:-1]).sum())
    print(f'{s:>2}  {int(seg.sum()):>3}  {rep:>3}')
