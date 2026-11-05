# 스톡 6170792(운전석 거치대의 빈 흰 폰 화면)에 배송 목록 화면을 원근 맞춰 합성한다.
# python -I phone_comp.py <원본.mp4> <출력.mp4> <ffmpeg> <글꼴.otf> [미리보기 폴더]
# 프레임마다 화면(밝고 채도 낮은 영역)을 찾아 네 모서리를 잡고, 시간축으로 고른 뒤 덮는다.
# 엄지가 가린 곳·노치·둥근 모서리는 화면 마스크 밖이라 원본이 남는다. 원래 화면의 밝기 얼룩을 곱해 조명을 맞춘다.
import sys, subprocess, numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

src, dst, ffmpeg, font = sys.argv[1:5]
preview = sys.argv[5] if len(sys.argv) > 5 else None
ROI = (250, 450, 900, 1350)  # x0, y0, x1, y1

def ui(w=600, h=1300):
    im = Image.new('RGB', (w, h), (246, 247, 249)); d = ImageDraw.Draw(im)
    F = lambda s, b=False: ImageFont.truetype(font.replace('Regular', 'Bold') if b else font, s)
    d.text((40, 30), '2:47', fill=(20, 20, 20), font=F(30, True))
    d.text((40, 120), '오늘 배송', fill=(20, 22, 28), font=F(52, True))
    d.text((40, 190), '23건 중 7건 완료', fill=(110, 116, 128), font=F(30))
    d.rounded_rectangle((40, 245, w - 40, 257), 6, fill=(225, 228, 233)); d.rounded_rectangle((40, 245, 40 + (w - 80) * 7 // 23, 257), 6, fill=(52, 120, 246))
    rows = [('마루동 5구역', '302호 · 3개', 0), ('다솜동 3구역', '1층 경비실', 0), ('가람동 1구역', '문 앞 · 1개', 0), ('나래동 2구역', '501호 · 2개', 0), ('라온동 4구역', '택배함', 0), ('하늘동 7구역', '1203호', 0)]
    y = 300
    for i, (a, b, _) in enumerate(rows):
        hl = i == 0
        d.rounded_rectangle((30, y, w - 30, y + 128), 22, fill=(255, 255, 255) if not hl else (234, 241, 255))
        d.ellipse((58, y + 42, 102, y + 86), outline=(52, 120, 246) if hl else (200, 204, 212), width=5)
        d.text((126, y + 26), a, fill=(20, 22, 28), font=F(36, True))
        d.text((126, y + 76), b, fill=(120, 126, 138), font=F(26))
        y += 146
    d.rounded_rectangle((40, h - 170, w - 40, h - 70), 26, fill=(52, 120, 246))
    t = '배송 완료'; f = F(38, True); tw = d.textlength(t, font=f)
    d.text(((w - tw) / 2, h - 145), t, fill=(255, 255, 255), font=f)
    return cv2.cvtColor(np.array(im), cv2.COLOR_RGB2BGR)

def order(p):
    p = p[np.argsort(p[:, 1])]; top = p[:2][np.argsort(p[:2, 0])]; bot = p[2:][np.argsort(p[2:, 0])]
    return np.array([top[0], top[1], bot[1], bot[0]], np.float32)  # TL TR BR BL

def detect(f):
    x0, y0, x1, y1 = ROI
    hsv = cv2.cvtColor(f[y0:y1, x0:x1], cv2.COLOR_BGR2HSV)
    m = ((hsv[..., 1] < 40) & (hsv[..., 2] > 150)).astype(np.uint8)
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    n, lab, st, _ = cv2.connectedComponentsWithStats(m)
    if n < 2: return None, None
    k = 1 + np.argmax(st[1:, cv2.CC_STAT_AREA])
    if st[k, cv2.CC_STAT_AREA] < 20000: return None, None
    comp = (lab == k).astype(np.uint8)
    hull = cv2.convexHull(cv2.findNonZero(comp))
    peri = cv2.arcLength(hull, True)
    for e in np.linspace(0.01, 0.08, 30):
        ap = cv2.approxPolyDP(hull, e * peri, True)
        if len(ap) == 4: break
    else:
        ap = cv2.boxPoints(cv2.minAreaRect(hull)).reshape(-1, 1, 2)
    full = np.zeros(f.shape[:2], np.uint8); full[y0:y1, x0:x1] = comp
    return order(ap.reshape(4, 2).astype(np.float32) + [x0, y0]), full

cap = cv2.VideoCapture(src); fps = cap.get(cv2.CAP_PROP_FPS)
frames = []
while True:
    ok, f = cap.read()
    if not ok: break
    frames.append(f)
H, W = frames[0].shape[:2]
quads, masks = zip(*[detect(f) for f in frames])
# 빠진 프레임은 이웃으로 채우고, 모서리를 시간축으로 고른다(5프레임 이동평균)
q = np.array([x if x is not None else np.full((4, 2), np.nan, np.float32) for x in quads])
idx = np.arange(len(q))
for c in range(4):
    for j in range(2):
        v = q[:, c, j]; good = ~np.isnan(v)
        v[:] = np.interp(idx, idx[good], v[good])
        q[:, c, j] = np.convolve(np.pad(v, 2, mode='edge'), np.ones(5) / 5, 'valid')

U = ui(); uh, uw = U.shape[:2]
srcq = np.array([[0, 0], [uw, 0], [uw, uh], [0, uh]], np.float32)
proc = subprocess.Popen([ffmpeg, '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'bgr24', '-s', f'{W}x{H}', '-r', str(fps), '-i', '-',
                         '-c:v', 'libx264', '-crf', '12', '-preset', 'medium', '-pix_fmt', 'yuv420p', dst], stdin=subprocess.PIPE)
for i, f in enumerate(frames):
    if True:  # 화면을 못 찾은 프레임도 이웃 프레임에서 채운 모서리로 덮는다
        M = cv2.getPerspectiveTransform(srcq, q[i])
        warped = cv2.warpPerspective(U, M, (W, H), flags=cv2.INTER_AREA)
        warped = cv2.GaussianBlur(warped, (0, 0), 0.7)
        inside = cv2.warpPerspective(np.full((uh, uw), 255, np.uint8), M, (W, H))
        x0, y0, x1, y1 = ROI
        hsv = cv2.cvtColor(f, cv2.COLOR_BGR2HSV)
        scr = ((hsv[..., 1] < 48) & (hsv[..., 2] > 135)).astype(np.float32)
        scr[:y0] = 0; scr[y1:] = 0; scr[:, :x0] = 0; scr[:, x1:] = 0
        # 손(엄지·손톱)은 화면보다 붉다: R-B가 큰 곳을 넓혀 화면에서 뺀다
        fi = f.astype(np.int16)
        skin = ((fi[..., 2] - fi[..., 0]) > 10).astype(np.uint8)
        skin = cv2.dilate(cv2.morphologyEx(skin, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8)), np.ones((7, 7), np.uint8))
        scr[skin > 0] = 0
        a = cv2.GaussianBlur(scr * (inside / 255.0), (0, 0), 1.2)
        g = cv2.cvtColor(f, cv2.COLOR_BGR2GRAY).astype(np.float32)
        ref = np.percentile(g[scr > 0], 95) if scr.sum() > 0 else 230
        light = np.clip(cv2.GaussianBlur(g, (0, 0), 6) / ref, 0.6, 1.08)[..., None]
        comp = np.clip(warped.astype(np.float32) * light * 0.97, 0, 255)
        out = (f.astype(np.float32) * (1 - a[..., None]) + comp * a[..., None]).astype(np.uint8)
    proc.stdin.write(out.tobytes())
    if preview and i in (int(2.6 * fps), int(8.6 * fps), int(11.1 * fps)):
        cv2.imwrite(f'{preview}/comp_{i}.png', out[500:1300, 250:900])
proc.stdin.close(); proc.wait()
print('frames', len(frames), 'missed', sum(m is None for m in masks))
