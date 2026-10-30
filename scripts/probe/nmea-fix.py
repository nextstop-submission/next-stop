"""에뮬레이터 위치를 NMEA 문장으로 보낸다(체크섬을 맞게 계산).

    python3 scripts/probe/nmea-fix.py <위도> <경도> <초>   → $GPGGA, $GPRMC 두 줄

`adb emu geo fix`가 만든 NMEA는 Pleos 내비가 'NMEA checksum mismatch'로 버렸다(CI 5회차 navi-log) →
`adb emu geo nmea '<문장>'`으로 직접 보낸다. <초>는 UTC 시각(0시부터 초) — 문장마다 늘려야 새 위치로 본다.
"""
import sys
from functools import reduce


def checksum(body):
    return "%02X" % reduce(lambda a, c: a ^ ord(c), body, 0)


def deg_min(v, is_lat):
    a = abs(v)
    d = int(a)
    m = (a - d) * 60
    if is_lat:
        return f"{d:02d}{m:07.4f}", "N" if v >= 0 else "S"
    return f"{d:03d}{m:07.4f}", "E" if v >= 0 else "W"


lat, lng, sec = float(sys.argv[1]), float(sys.argv[2]), int(sys.argv[3])
t = f"{sec // 3600 % 24:02d}{sec // 60 % 60:02d}{sec % 60:02d}.00"
la, ns = deg_min(lat, True)
lo, ew = deg_min(lng, False)
for body in (f"GPGGA,{t},{la},{ns},{lo},{ew},1,08,0.9,30.0,M,0.0,M,,",
             f"GPRMC,{t},A,{la},{ns},{lo},{ew},15.0,0.0,301026,,,A"):
    print(f"${body}*{checksum(body)}")
