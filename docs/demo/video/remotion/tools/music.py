# 원곡 배경음악 합성(AI 아님, 샘플 없음). 100BPM, D장조 IV-iii-ii-V. 48kHz 스테레오 wav.
# python -I music.py <출력.wav> [씨앗]
# 구조(다음집 숏폼 5차와 같은 박자 격자): 첫 박 0.08초 · 1마디 2.4초
#   인트로 8마디(0.08~19.28초): 일렉피아노 화음 + 하이햇 + 반박자 킥
#   본편 8마디(19.28~38.48초): 킥·스네어·하이햇·베이스·짧은 멜로디
#   아웃트로 4마디(38.48~48.08초): 하나씩 빠지고 마지막 화음이 울리다 끝난다
import sys, numpy as np
from scipy.signal import butter, sosfilt, fftconvolve
from scipy.io import wavfile

SR = 48000; BPM = 100; BEAT = 60 / BPM; BAR = 4 * BEAT; T0 = 0.08
rng = np.random.default_rng(int(sys.argv[2]) if len(sys.argv) > 2 else 11)
N = int((T0 + 20 * BAR + 1.6) * SR)
L = np.zeros((2, N))

def mtof(m): return 440 * 2 ** ((m - 69) / 12)
def t(sec): return np.arange(int(sec * SR)) / SR
def filt(x, kind, f, order=2): return sosfilt(butter(order, f, kind, fs=SR, output='sos'), x)
def place(bus, x, sec, pan=0.0, gain=1.0):
    i = int(sec * SR); n = min(len(x), N - i)
    if n <= 0: return
    bus[0, i:i + n] += x[:n] * gain * np.sqrt(0.5 * (1 - pan)) * 1.414
    bus[1, i:i + n] += x[:n] * gain * np.sqrt(0.5 * (1 + pan)) * 1.414
def hum(sec, ms=7): return sec + rng.normal(0, ms / 1000)

# 일렉피아노(FM 2연산자: 몸통 1:1 + 짧은 '틴' 14:1), 트레몰로
def ep(m, dur, vel=0.6):
    f = mtof(m); x = t(dur + 1.2)
    i1 = (1.8 * vel) * np.exp(-x / 0.35) + 0.3
    tine = 0.6 * vel * np.exp(-x / 0.05)
    mod = np.sin(2 * np.pi * f * x) * i1 + np.sin(2 * np.pi * 14 * f * x) * tine
    car = np.sin(2 * np.pi * f * x + mod)
    env = np.minimum(1, x / 0.004) * np.exp(-x / (1.6 if m < 60 else 1.1))
    rel = np.clip((dur + 0.25 - x) / 0.25, 0, 1)
    trem = 1 + 0.12 * np.sin(2 * np.pi * 4.6 * x)
    return car * env * rel * trem * vel

def bass(m, dur, vel=0.8):
    f = mtof(m); x = t(dur + 0.15)
    y = np.sin(2 * np.pi * f * x) + 0.35 * np.sin(4 * np.pi * f * x) + 0.12 * np.sin(6 * np.pi * f * x)
    env = np.minimum(1, x / 0.01) * np.exp(-x / 0.9) * np.clip((dur + 0.08 - x) / 0.08, 0, 1)
    return np.tanh(1.6 * y * env) * vel

def pluck(m, vel=0.5):
    f = mtof(m); x = t(1.4)
    y = np.sin(2 * np.pi * f * x + 1.2 * np.exp(-x / 0.08) * np.sin(2 * np.pi * 3 * f * x))
    return y * np.minimum(1, x / 0.002) * np.exp(-x / 0.45) * vel

def kick(vel=1.0):
    x = t(0.45); f = 48 + 75 * np.exp(-x / 0.035)
    ph = 2 * np.pi * np.cumsum(f) / SR
    body = np.sin(ph) * np.exp(-x / 0.16)
    click = filt(rng.standard_normal(len(x)), 'highpass', 2000) * np.exp(-x / 0.003) * 0.25
    return np.tanh(1.4 * (body + click)) * vel

def snare(vel=0.7):
    x = t(0.3)
    tone = np.sin(2 * np.pi * 185 * x) * np.exp(-x / 0.05) * 0.6
    nz = filt(rng.standard_normal(len(x)), 'bandpass', [1200, 7000]) * np.exp(-x / 0.09)
    return (tone + nz * 0.9) * vel

def hat(vel=0.3, open_=False):
    x = t(0.25 if open_ else 0.06)
    nz = filt(rng.standard_normal(len(x)), 'highpass', 7500, 4)
    return nz * np.exp(-x / (0.08 if open_ else 0.012)) * vel

# D장조: Gmaj7 / F#m7 / Em7 / A7sus4→A7 (근음, 보이싱)
CHORDS = [(43, [59, 62, 66, 69]), (42, [57, 61, 64, 69]), (40, [55, 59, 62, 66]), (45, [55, 59, 62, 64])]
LAST = (38, [57, 62, 66, 69])  # 끝 화음 Dmaj(add9 느낌)
MOTIF = [(0, 74), (0.75, 76), (1.5, 78), (2.5, 81), (3.0, 78)]  # 박 단위, 2마디에 한 번

keys, drums, low = np.zeros_like(L), np.zeros_like(L), np.zeros_like(L)
kicks = []
for b in range(20):
    s = T0 + b * BAR
    root, voic = CHORDS[b % 4] if b < 19 else LAST
    sec = 'intro' if b < 8 else ('main' if b < 16 else 'outro')
    # 화음: 마디 첫 박에 길게, 본편은 3박 뒤에 짧게 한 번 더(당김)
    dur = BAR * (2.2 if b == 19 else 0.95)
    for k, m in enumerate(voic):
        place(keys, ep(m, dur, 0.55 if sec != 'outro' else 0.45), hum(s + k * 0.012), pan=-0.25 + 0.17 * k)
    if sec == 'main':
        for k, m in enumerate(voic[1:]):
            place(keys, ep(m, BEAT * 0.8, 0.32), hum(s + 2.5 * BEAT + k * 0.01), pan=0.2 - 0.15 * k)
    # 드럼
    if b < 19:
        for q in range(8):  # 스윙 8분음표 하이햇
            sw = 0.0 if q % 2 == 0 else 0.07 * BEAT
            v = (0.32 if q % 2 == 0 else 0.2) * rng.uniform(0.8, 1.1)
            if sec == 'outro' and b >= 18: v *= 0.5
            place(drums, hat(v, open_=(q == 7 and sec == 'main' and b % 2 == 1)), hum(s + q * BEAT / 2 + sw, 4), pan=0.3)
        if sec == 'intro':
            for q in (0, 2):
                kicks.append(s + q * BEAT); place(drums, kick(0.7), s + q * BEAT)
        elif sec == 'main' or (sec == 'outro' and b < 18):
            for q in (0, 1.5, 2):
                kicks.append(s + q * BEAT); place(drums, kick(0.95 if q != 1.5 else 0.6), s + q * BEAT)
            for q in (1, 3):
                place(drums, snare(0.55 * rng.uniform(0.9, 1.05)), hum(s + q * BEAT, 5), pan=-0.05)
    else:
        kicks.append(s); place(drums, kick(0.8), s)
    # 베이스(본편·아웃트로 앞 2마디)
    if sec == 'main' or (sec == 'outro' and b < 18):
        place(low, bass(root, BEAT * 1.4), hum(s, 4))
        place(low, bass(root + 7 if b % 2 else root + 12, BEAT * 0.45, 0.6), hum(s + 1.5 * BEAT, 4))
        place(low, bass(root, BEAT * 1.2, 0.75), hum(s + 2 * BEAT, 4))
    if b == 19: place(low, bass(root, BAR * 1.5, 0.8), s)
    # 멜로디(본편 짝수 마디)
    if sec == 'main' and b % 2 == 0:
        for q, m in MOTIF:
            place(keys, pluck(m, 0.33 * rng.uniform(0.85, 1.05)), hum(s + q * BEAT, 6), pan=0.35)

# 킥에 맞춘 사이드체인(화음·베이스가 킥 자리에서 살짝 비킨다)
duck = np.ones(N)
for k in kicks:
    i = int(k * SR); x = t(0.3); n = min(len(x), N - i)
    duck[i:i + n] = np.minimum(duck[i:i + n], 1 - 0.35 * np.exp(-x[:n] / 0.09))
keys *= duck; low *= duck

# 공간감: 지수 감쇠 잡음 IR(1.3초) 리버브를 화음에만
irx = t(1.3); ir = rng.standard_normal((2, len(irx))) * np.exp(-irx / 0.35)
ir = np.stack([filt(ir[0], 'lowpass', 5000), filt(ir[1], 'lowpass', 5000)]) * 0.012
wet = np.stack([fftconvolve(keys[c], ir[c])[:N] for c in range(2)])
keys = filt(keys, 'lowpass', 9000) + wet

mix = 0.55 * keys + 0.5 * low + 0.6 * drums
mix = filt(filt(mix, 'highpass', 30), 'lowpass', 15000)
mix = mix + 0.35 * filt(mix, 'highpass', 3000)  # 위쪽(3kHz~)을 조금 살린다
# 테이프 느낌: 약한 포화 + 아주 느린 음높이 흔들림 대신 잔잔한 잡음 바닥
mix = np.tanh(mix * 1.3) / 1.3 + filt(rng.standard_normal((2, N)), 'lowpass', 6000) * 0.0012
end = int((T0 + 20 * BAR + 1.4) * SR)
mix[:, end - int(1.0 * SR):end] *= np.linspace(1, 0, int(1.0 * SR))
mix[:, end:] = 0
mix /= np.abs(mix).max() / 0.89
wavfile.write(sys.argv[1], SR, (mix.T * 32767).astype(np.int16))
print('len', round(N / SR, 2), 'main from', round(T0 + 8 * BAR, 2), 'outro from', round(T0 + 16 * BAR, 2))
