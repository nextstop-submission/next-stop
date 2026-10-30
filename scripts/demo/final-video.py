"""2분 시연 영상 초안을 다시 만든다(10/30 초안은 폐기, 최종본은 docs/demo/video/nextstop-demo.mp4 — 재료 재현용). 본체는 Pleos 공식 에뮬레이터 실화면(C, GitHub Actions 녹화)이다.

    python3 scripts/demo/final-video.py [출력.mp4]

- 실화면: docs/probe-results/ci-20261030-0418/vc-drive-clean.mp4 (Virtual Console로 기어·운전석 문 조작)
- 내비 목적지 정지 화면: docs/probe-results/20261030-auto-host/demo-3-next.png (같은 공식 에뮬레이터)
- C에 없는 계획·정산 화면만 앱 화면 렌더링(docs/screens/)으로 채우고, 화면에 '렌더링(에뮬레이터 아님)'을 밝힌다.
- 소리 없음(-an), 메타데이터 비움(익명). 필요: ffmpeg, Pillow, 한글 글꼴(Noto Sans CJK — FONT 환경 변수로 바꿀 수 있음).
"""
import os
import shutil
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "demo-output/nextstop-final-2min.mp4")
REAL = os.path.join(ROOT, "docs/probe-results/ci-20261030-0418/vc-drive-clean.mp4")
NAVI = os.path.join(ROOT, "docs/probe-results/20261030-auto-host/demo-3-next.png")
PLAN = os.path.join(ROOT, "docs/screens/01-plan.png")
SETTLE = os.path.join(ROOT, "docs/screens/07-settlement.png")

W, H, FPS = 1920, 1080, 30
BG = (16, 20, 24)
GREEN = (155, 231, 168)
GRAY = (150, 158, 166)
# 화면은 위쪽에 1664×936으로 두고, 아래 띠(120px)에 자막을 쓴다 — 자막이 화면 내용을 가리지 않게
FX, FY, FW, FH = 128, 16, 1664, 936

SRC_REAL = "Pleos Connect 공식 에뮬레이터 v2.1.0 실화면 · Virtual Console로 기어·운전석 문 조작"
SRC_RENDER = "앱 화면 렌더링(에뮬레이터 아님) · 샘플 배송지"


def font(size, bold=True):
    path = os.environ.get("FONT")
    if not path:
        names = ["Bold", "Regular"] if bold else ["Regular", "Bold"]
        for cand in [f"/usr/share/fonts/opentype/noto/NotoSansCJK-{n}.ttc" for n in names]:
            if os.path.exists(cand):
                path = cand
                break
    if not path:
        sys.exit("한글 글꼴을 찾지 못했습니다(FONT=경로)")
    # .ttc 안 순서: 0=JP, 1=KR, 2=SC … — 한글 글리프 모양을 위해 KR
    return ImageFont.truetype(path, size, index=1)


def fit(draw, text, size, maxw, bold=True):
    f = font(size, bold)
    while draw.textlength(text, font=f) > maxw and size > 20:
        size -= 2
        f = font(size, bold)
    return f


def band(caption, source):
    """화면 아래 자막 띠(투명 배경 위 1920×1080 PNG)."""
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([0, FY + FH + 4, W, H], fill=BG + (255,))
    f = fit(d, caption, 46, W - 120)
    d.text((W / 2, FY + FH + 46), caption, font=f, fill="white", anchor="mm")
    if source:
        s = font(24, False)
        d.text((W / 2, FY + FH + 98), source, font=s, fill=GRAY, anchor="mm")
    return im


def card(lines, background=None):
    """제목·설명 카드. lines = [(글, 크기, 색)] 또는 [[(글, 색), …], 크기] 한 줄 여러 색."""
    if background:
        im = Image.open(background).convert("RGB").resize((W, H))
        im = Image.blend(im, Image.new("RGB", (W, H), BG), 0.9)
    else:
        im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    total = sum(int(ln[1] * 1.7) for ln in lines)
    y = (H - total) / 2
    for parts, size, *rest in lines:
        if isinstance(parts, str):
            parts = [(parts, rest[0] if rest else "white")]
        text = "".join(p for p, _ in parts)
        f = fit(d, text, size, W - 160)
        x = (W - d.textlength(text, font=f)) / 2
        for p, color in parts:
            d.text((x, y), p, font=f, fill=color)
            x += d.textlength(p, font=f)
        y += int(size * 1.7)
    return im


def run(args):
    subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y"] + args, check=True)


def main():
    tmp = tempfile.mkdtemp()
    parts = []

    def still(name, img, sec):
        p = os.path.join(tmp, name + ".png")
        img.save(p)
        out = os.path.join(tmp, name + ".mp4")
        run(["-loop", "1", "-t", str(sec), "-i", p, "-r", str(FPS), "-pix_fmt", "yuv420p",
             "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", out])
        parts.append(out)

    def screen(name, src_png, sec, caption, source, crop=None):
        im = Image.open(src_png).convert("RGB")
        if crop:
            im = im.crop(crop)
        im.thumbnail((FW, FH))
        canvas = Image.new("RGB", (W, H), BG)
        canvas.paste(im, (FX + (FW - im.width) // 2, FY + (FH - im.height) // 2))
        canvas.paste(band(caption, source), (0, 0), band(caption, source))
        still(name, canvas, sec)

    def real(name, start, end, caption, speed=1.0, source=SRC_REAL):
        b = os.path.join(tmp, name + "-band.png")
        band(caption, source).save(b)
        out = os.path.join(tmp, name + ".mp4")
        vf = (f"[0:v]trim={start}:{end},setpts=(PTS-STARTPTS)/{speed},fps={FPS},"
              f"scale={FW}:{FH}:force_original_aspect_ratio=decrease,"
              f"pad={W}:{H}:{FX}+({FW}-iw)/2:{FY}+({FH}-ih)/2:color=0x101418[v];[v][1:v]overlay=0:0")
        run(["-i", REAL, "-loop", "1", "-i", b, "-filter_complex", vf, "-t", f"{(end - start) / speed:.2f}",
             "-an", "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", out])
        parts.append(out)

    # 0:00 제목
    still("00-title", card([("다음집", 120, "white"),
                            ("자기 차로 택배 부업을 하는 사람을 위한 Pleos Connect 앱", 44, GREEN)]), 5)
    # 0:05 문제 → 해결 한 줄 (배경은 실화면의 완료 확인 장면을 흐리게)
    bgp = os.path.join(tmp, "bg.png")
    run(["-ss", "43", "-i", REAL, "-frames:v", "1", bgp])
    still("01-problem", card([
        ([("정차할 때마다 폰 꺼내 완료 체크·주소 입력", "white"), ("  →  문 닫고 D 넣으면 끝", GREEN)], 54),
        ("1건 완료 기록과 다음 집 내비 전달을, 차량 신호(기어·운전석 문)만으로", 34, GRAY),
    ], background=bgp), 10)
    # 0:15 출발 전 계획(C에 없는 화면 → 렌더링, 표시)
    screen("02-plan", PLAN, 6, "출발 전, 정차 상태에서 배송지 순서를 정리합니다", SRC_RENDER + " · 계획 화면",
           crop=(0, 100, 2560, 1440))
    # 0:21 운행 시작
    real("03-start", 8.4, 14.3, "운행 시작 · 화면에는 다음 배송지와 남은 건수")
    # 0:27 내비로 목적지 전달
    screen("04-navi", NAVI, 5, "목적지는 한 곳씩 Pleos 내비로 넘어갑니다 (NaviHelper)",
           "Pleos Connect 공식 에뮬레이터 실화면 · 내비가 앞으로 나와 목적지를 띄운 장면")
    # 0:32 주행 중 화면 최소화
    real("05-driving", 21.6, 29.4, "주행 중(D)에는 큰 글자 두 줄만 · 버튼 없음",
         source="주행 제한 준수: 주행 중엔 조작할 것을 없앤다 · " + SRC_REAL)
    # 0:40 1건째 — 실제 속도
    real("06-park", 29.4, 38.2, "도착해 P → 운전석 문을 열고 배송")
    real("07-door", 38.2, 42.3, "돌아와 문을 닫으면 → 'D를 넣으면 완료'")
    real("08-done", 42.3, 46.8, "D를 넣는 순간 1건 자동 완료 — 화면은 한 번도 누르지 않았습니다")
    real("09-next", 46.8, 50.0, "다음 집이 바로 뜨고, 내비로 넘어갑니다")
    # 1:00 2·3건째 — 2배속
    real("10-more", 50.0, 96.5, "2건째 · 3건째도 P · 문 · D만으로 (2배속)", speed=2.0)
    # 1:23 신호 대기는 세지 않음
    still("11-rule", card([
        ("문을 열지 않은 정차(신호 대기)는 완료로 치지 않습니다", 50, "white"),
        ("P → D만 있고 운전석 문이 열리고 닫히지 않았으면 그대로 · 판정 규칙은 단위 테스트로 검증", 30, GRAY),
    ], background=bgp), 7)
    # 1:30 정산(C에 없는 화면 → 렌더링, 표시. 위쪽 요약만)
    screen("12-settle", SETTLE, 7, "끝나면 자동 완료 건수와, 연료비를 뺀 순수익",
           SRC_RENDER + " · 정산 화면", crop=(0, 100, 2560, 1080))
    # 1:37 기술 요약
    still("13-tech", card([
        ("위치 없이, 차량 신호만으로", 64, "white"),
        ("Pleos Vehicle SDK  운전석 문 · 주행거리(Odometer)", 38, GREEN),
        ("AAOS Car API  기어 P/D · 주행 제한(CarUxRestrictions)", 38, GREEN),
        ("Pleos NaviHelper  다음 목적지 전달", 38, GREEN),
        ("터널·지하주차장에서도 같은 판정 · 위치 권한 없음 · 외부 서버 없음", 30, GRAY),
    ]), 12)
    # 1:49 끝
    still("14-end", card([("차가 업무를 알아보면,", 72, "white"),
                          ("사람은 화면을 만질 필요가 없습니다", 72, GREEN),
                          ("다음집", 40, GRAY)]), 6)

    listing = os.path.join(tmp, "list.txt")
    with open(listing, "w") as f:
        f.writelines(f"file '{p}'\n" for p in parts)
    os.makedirs(os.path.dirname(OUT) or ".", exist_ok=True)
    run(["-f", "concat", "-safe", "0", "-i", listing, "-c", "copy", "-an", "-map_metadata", "-1",
         "-movflags", "+faststart", OUT])
    shutil.rmtree(tmp)
    dur = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", OUT],
                         capture_output=True, text=True).stdout.strip()
    print(f"영상: {OUT} ({os.path.getsize(OUT) // 1024}KB, {float(dur):.1f}초)")


if __name__ == "__main__":
    main()
