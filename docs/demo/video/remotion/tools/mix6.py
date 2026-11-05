# 숏폼 6차(Short6.jsx, NextStopShortV6)의 소리. 화면은 소리 없이 렌더하고 이 파일로 섞어 붙인다(소리의 원본은 여기 하나).
# python -I mix6.py <public 폴더> <음악 wav> <ffmpeg> <출력.wav>
# 클릭·지지직 방지: 모든 구간 끝에 짧은 페이드, 효과음 끝 5ms 페이드, 음악 되감기 자리는 30ms 겹쳐 넘긴다.
import sys, subprocess, numpy as np

pub, music, ffmpeg, out = sys.argv[1:5]
SR, TOTAL, FPS = 48000, 41.0, 30
N = int(TOTAL * SR)

def load(path):
    p = subprocess.run([ffmpeg, '-v', 'error', '-i', path, '-f', 'f32le', '-ac', '2', '-ar', str(SR), '-'], capture_output=True, check=True)
    return np.frombuffer(p.stdout, np.float32).reshape(-1, 2).T.astype(np.float64)

def snap(sec): return round(sec * FPS) / FPS  # 화면 프레임에 맞춘다

mix = np.zeros((2, N))
def lay(src, at, trim, dur, vol, fade_in=0.01, fade_out=0.01):
    a = int(snap(at) * SR); s = int(trim * SR); n = min(int(dur * SR), src.shape[1] - s, N - a)
    if n <= 0: return
    t = np.arange(n) / SR
    v = vol(t) if callable(vol) else np.full(n, float(vol))
    v = v * np.clip(t / max(fade_in, 1e-4), 0, 1) * np.clip((n / SR - t) / max(fade_out, 1e-4), 0, 1)
    mix[:, a:a + n] += src[:, s:s + n] * v

M = load(music)
lay(M, 0, 0.08, 7.2, 0.55, fade_out=0.03)                                   # 1막: 7.2초에 멈춘다
lay(M, 10.8, 19.28, 26.4 - 10.8 + 0.03, 0.7, fade_in=0.02, fade_out=0.03)    # 본편
lay(M, 26.4 - 0.03, 25.28 - 0.03, TOTAL - 26.37, lambda t: np.interp(t, [0, 12.63, 14.63], [0.7, 0.7, 0]), fade_in=0.03)  # 4마디 되감기
amb = load(f'{pub}/s16420406.mp4')                                          # 바깥 소리(비 오는 거리)
lay(amb, 0, 2, 10.8, lambda t: np.interp(t, [0, 7.2, 10.2, 10.8], [0.18, 0.3, 0.3, 0]), fade_in=0.05)
lay(amb, 26.4, 8, 2.4, 0.22, fade_in=0.08, fade_out=0.08)
lay(amb, 34.2, 12, 3.6, 0.18, fade_in=0.08, fade_out=0.15)

SFX = [(0.12, 'buzz', 0.55), (1.5, 'tap', 0.95), (1.82, 'tap', 0.9), (3.95, 'tap', 0.8), (4.86, 'buzz', 0.6), (6.9, 'buzz', 0.45),
       (13.4, 'gear', 0.9), (14.45, 'door_open', 0.75), (17.8, 'door_close', 1.0), (19.24, 'gear', 0.9), (19.82, 'chime', 0.7),
       (25.22, 'chime', 0.35)]
cache = {}
for at, name, v in SFX:
    if name not in cache: cache[name] = load(f'{pub}/{name}.wav')
    lay(cache[name], at, 0, cache[name].shape[1] / SR, v, fade_in=0.002, fade_out=0.005)

peak = np.abs(mix).max()
subprocess.run([ffmpeg, '-v', 'error', '-y', '-f', 'f32le', '-ac', '2', '-ar', str(SR), '-i', '-', '-c:a', 'pcm_s16le', out],
               input=np.clip(mix, -1, 1).T.astype(np.float32).tobytes(), check=True)
# 1ms 단위로 갑자기 튀는 곳(클릭)이 있는지 본다: 이웃 표본 차이가 0.25 넘는 곳 수
d = np.abs(np.diff(mix, axis=1)).max(axis=0)
print('peak', round(20 * np.log10(peak + 1e-9), 1), 'dBFS · 큰 표본 점프', int((d > 0.25).sum()))
