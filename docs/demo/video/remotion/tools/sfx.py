# 다음집 숏폼 5차 효과음 합성(48kHz 모노 wav). python -I sfx.py <출력 폴더>
import sys, numpy as np
from scipy.signal import butter, sosfilt
from scipy.io import wavfile

SR = 48000
rng = np.random.default_rng(7)
out = sys.argv[1]

def t(sec): return np.arange(int(sec * SR)) / SR
def env(sec, attack, decay):
    x = t(sec)
    return np.minimum(1, x / max(attack, 1e-4)) * np.exp(-x / decay)
def filt(x, kind, f, order=2):
    return sosfilt(butter(order, f, kind, fs=SR, output='sos'), x)
def noise(sec): return rng.standard_normal(int(sec * SR))
def pad(x, sec):
    y = np.zeros(int(sec * SR)); y[:len(x)] = x[:len(y)]; return y
def at(dst, x, sec):
    i = int(sec * SR); n = min(len(x), len(dst) - i); dst[i:i + n] += x[:n]
def save(name, x, peak_db=-3):
    x = x / (np.abs(x).max() + 1e-9) * 10 ** (peak_db / 20)
    wavfile.write(f'{out}/{name}.wav', SR, (x * 32767).astype(np.int16))

def click(sec=0.012, lo=1500, hi=9000, decay=0.002):
    return filt(noise(sec), 'bandpass', [lo, hi]) * env(sec, 0.0002, decay)

# 휴대폰 진동: 175Hz 모터 + 표면 덜컹임, 두 번
def buzz():
    x = np.zeros(int(1.0 * SR))
    for s in (0.0, 0.5):
        d = 0.34; tt = t(d)
        motor = np.sin(2 * np.pi * 172 * tt + 0.4 * np.sin(2 * np.pi * 9 * tt))
        rattle = filt(noise(d), 'bandpass', [900, 3500]) * (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * 172 * tt)))
        e = np.minimum(1, tt / 0.02) * np.minimum(1, (d - tt) / 0.03)
        at(x, (motor * 0.8 + rattle * 0.25) * e, s)
    return filt(x, 'highpass', 120)

# 화면 톡(손끝)
def tap():
    x = click(0.03, 800, 6000, 0.004) + 0.4 * np.sin(2 * np.pi * 240 * t(0.03)) * env(0.03, 0.0005, 0.006)
    return pad(x, 0.08)

# 기어 레버 철컥: 둔한 몸통 + 걸림쇠 두 번
def gear():
    x = np.zeros(int(0.35 * SR))
    body = filt(noise(0.12), 'lowpass', 500) * env(0.12, 0.001, 0.025) + 0.6 * np.sin(2 * np.pi * 95 * t(0.12)) * env(0.12, 0.001, 0.03)
    at(x, body, 0.0)
    at(x, click(0.015, 1200, 7000, 0.0025), 0.004)
    at(x, 0.55 * click(0.015, 1500, 8000, 0.002), 0.055)
    return filt(x, 'lowpass', 5000)  # 걸림쇠 소리가 날카로워 지지직처럼 들리지 않게 위를 깎는다

# 문 열림: 손잡이 당김 + 걸쇠 풀림 + 바깥 공기
def door_open():
    x = np.zeros(int(1.2 * SR))
    at(x, 0.5 * click(0.02, 600, 4000, 0.004), 0.0)
    at(x, click(0.02, 1000, 6000, 0.003), 0.09)
    at(x, 0.7 * filt(noise(0.08), 'lowpass', 900) * env(0.08, 0.001, 0.02), 0.1)
    air = filt(noise(1.1), 'bandpass', [200, 2500]) * np.minimum(1, t(1.1) / 0.25) * np.exp(-t(1.1) / 0.6) * 0.18
    at(x, air, 0.1)
    return x

# 문 닫힘: 묵직한 쿵 + 걸쇠 + 짧은 떨림
def door_close():
    x = np.zeros(int(0.8 * SR))
    thump = filt(noise(0.35), 'lowpass', 260, 4) * env(0.35, 0.0015, 0.06) * 2.2
    sub = np.sin(2 * np.pi * 58 * t(0.35)) * env(0.35, 0.002, 0.07) * 1.2
    at(x, thump + sub, 0.0)
    at(x, 0.5 * click(0.02, 1500, 7000, 0.003), 0.008)
    rattle = filt(noise(0.2), 'bandpass', [300, 1200]) * env(0.2, 0.002, 0.04) * 0.3
    at(x, rattle, 0.03)
    return x

# 완료음: 차량 안내음 같은 부드러운 두 음(E6→A6), 종 배음
def chime():
    x = np.zeros(int(1.6 * SR))
    for s, f in ((0.0, 1318.5), (0.13, 1760.0)):
        tt = t(1.4)
        tone = sum(a * np.sin(2 * np.pi * f * m * tt) for m, a in ((1, 1), (2.01, 0.18), (3.0, 0.06)))
        at(x, tone * env(1.4, 0.004, 0.32) * 0.7, s)
    return x

# 방향지시등 똑딱(릴레이) 4회
def ticktock():
    x = np.zeros(int(3.2 * SR))
    for k in range(4):
        at(x, pad(click(0.01, 2000, 9000, 0.0015), 0.02) + 0.3 * click(0.02, 400, 1500, 0.004), k * 0.75)
        at(x, 0.7 * (pad(click(0.01, 1500, 7000, 0.0015), 0.02) + 0.3 * click(0.02, 300, 1200, 0.004)), k * 0.75 + 0.36)
    return x

save('buzz', buzz(), -4)
save('tap', tap(), -8)
save('gear', gear(), -3)
save('door_open', door_open(), -5)
save('door_close', door_close(), -2)
save('chime', chime(), -8)
save('tick', ticktock(), -10)
print('ok')
