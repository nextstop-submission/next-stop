# 숏폼 챌린지 영상 — 6차 `nextstop-shortform-v6.mp4`

6차는 5.1에서 세 가지를 고쳤다(소스 `../video/remotion/src/Short6.jsx`, 컴포지션 `NextStopShortV6`).
- **끊김**: 5.1은 60fps로 렌더해 25·24fps 실사가 2·3장씩 불규칙하게 반복됐다. 6차는 30fps로 렌더하고, 실사는 30/원본fps 배속으로 원본 프레임을 한 장씩 그대로 쓴다. `tools/frame_check.py`로 재면 실사 구간은 초마다 다른 화면 30장, 반복 0이다(5.1은 60장 중 20~29장, 반복 20회 이상).
- **지지직**: 움직이는 필름 그레인을 뺐다. 소리는 `tools/mix6.py` 하나로 섞는다. 모든 구간 끝에 짧은 페이드를 넣고, 효과음 끝은 5ms로 줄이고, 음악 되감기 자리는 30ms 겹쳐 넘긴다. 기어 소리의 날카로운 고음(5kHz 위)도 깎았다.
- **3D 차 장면**: 에뮬레이터의 3D 차 그림을 실제 촬영으로 바꿨다. 화물칸 안에서 본 문 열림·닫힘(Pexels 6405959), 현대 운전대를 다시 잡는 손(13675325)이다. 미국 지명이 읽히는 지도와 미국 거리 컷도 뺐다(9898435·6612460으로 교체). 음악은 직접 합성한 원곡(`tools/music.py`)이다.
- 결과: 41.0초, 1080×1920, 30fps, -14.0 LUFS, 최고 -1.5 dBFS.
- 다시 만들기: 아래 5판 절차와 같다. 다만 `NextStopShortV6`을 `--muted`로 렌더한 뒤 `python -I tools/mix6.py public public/music_orig.wav <ffmpeg> mix.wav`로 섞은 소리를 붙이고, 그레인은 넣지 않는다. `public/`에는 `s6405959.mp4`, `s13675325.mp4`, `s9898435.mp4`, `s6612460.mp4`를 더 둔다.

---

# 숏폼 챌린지 영상 — 5.1판 `nextstop-shortform-v5.1.mp4`

공모전 숏폼 챌린지(특별상)에 낼 세로 영상이다. 게시(Instagram 릴스 / YouTube Shorts)는 검토가 끝난 뒤에 한다. 설명란 글과 해시태그는 [shortform-설명.txt](shortform-설명.txt)에 있다.
영상 파일은 용량이 커서 이 저장소에 넣지 않았다(35MB). 아래 '다시 만들기'로 똑같이 만들 수 있다. 이 폴더의 `nextstop-shortform.mp4`는 4차판이다.

| 항목 | 값 |
|---|---|
| 형식 | 1080×1920, 60fps, H.264 + AAC 192k, 메타데이터 비움 |
| 길이 | 41.0초(공식 조건 30초~1분) |
| 음량 | -14 LUFS, 최고값 -1.3 dBFS |
| 소리 | 내레이션 없음. 음악(100BPM)과 동작 소리(휴대폰 진동·화면 톡·기어·문 열림/닫힘·완료음), 바깥 소리 |
| 글자 | 상자 없는 맨 글자, 카드마다 2줄 이하, 화면 위쪽 같은 자리 |
| 표기 | 사람이 나오는 장면은 '연출 장면 · 실제 촬영 자료'다. 거치대 폰에 배송 목록을 합성한 장면은 '· 폰 화면 합성'을 덧붙였다. 앱은 '실제 화면 · Pleos Connect 공식 에뮬레이터'이고, 정산만 '앱 화면 렌더링'이다 |

## 4차에서 바꾼 것

- AI 생성 실사(운전석·주차장)와 AI 음성 내레이션을 뺐다. 사람이 나오는 장면은 모두 실제 촬영 스톡(Pexels)이다.
- 구간 꼬리표(SPOT·SOLVE·SCENE), 알약 강조, 단계 격자, 이모지, 튕기는 애니메이션을 없앴다. 전환은 하드 컷만 쓰고, 컷은 음악 박자(0.6초)에 맞췄다.
- 앱 화면은 에뮬레이터 녹화(`../video/nextstop-emulator-real-signals.mp4`)를 크게 잘라 배경색과 이어지게 놓았다. 화면 테두리 상자는 쓰지 않았다.
- 스톡 속 거치대 폰은 화면이 비어 있었다. 그래서 프레임마다 화면을 찾아 원근을 맞춰 배송 목록을 합성했다. 엄지가 가린 곳은 원본이 그대로 남는다.

## 장면(영상 초)

| 시간 | 글자 | 화면 |
|---|---|---|
| 0~7.2 | 정차하면, 폰. / 주소 찾고, / 배송하고, / 완료 체크하고, / 또 폰. | 실제 촬영: 거치대 폰(배송 목록 합성)·지도 보는 손·트렁크 택배·송장 확인. 끝에 0.3초씩 빠르게 반복한다 |
| 7.2~10.8 | 하루 수십 번. / 이제, 차가 합니다. | 검은 화면. 음악을 멈추고 바깥 소리만 남긴다 |
| 10.8~12.6 | 다음집 · Pleos Connect 차량용 앱 | 로고. 음악이 다시 들어온다 |
| 12.6~23.4 | 기어 P, / 문 열고, / 닫고, / 다시 D. / 1건, 자동 완료. / 다음 집은, 바로 내비로. | 에뮬레이터 실화면과 실제 촬영(차 문 여닫기)을 번갈아 보여 준다. 3D 차 문 열림 → "D를 넣으면 완료" → ✓ 1/5 → 다음 집 |
| 23.4~26.4 | 화면은 한 번도 안 만집니다. | 2~4번째 집을 0.6초씩(실화면) |
| 26.4~31.2 | 달리는 동안엔 / 다음 집, 남은 건수. 딱 두 줄. | 운전대 1인칭(실제 촬영) → 주행 중 두 줄 화면(실화면) |
| 31.2~34.2 | 끝나면, 오늘 번 돈까지. | 정산 화면(렌더링) |
| 34.2~41 | 손은 운전대에. 다음 집은, 차가 챙깁니다. | 운전대 1인칭 → 끝 카드와 #HMG차량용앱개발공모전 |

## 다시 만들기

소스는 [`docs/demo/video/remotion/src/Short5.jsx`](../video/remotion/src/Short5.jsx)이고, 컴포지션 이름은 `NextStopShortV5`다. 재료는 저장소에 넣지 않는다(`.gitignore`). `public/`에 아래 파일을 둔다.

| 파일 | 출처 |
|---|---|
| `emu.mp4` | `docs/demo/video/nextstop-emulator-real-signals.mp4`를 복사 |
| `07-settlement.png` | `docs/screens/07-settlement.png`를 복사 |
| `c-done.png`, `c-drive.png` | 이미 `public/`에 있다(에뮬레이터 캡처) |
| `buzz/tap/gear/door_open/door_close/chime.wav` | `python -I tools/sfx.py public` (합성 효과음) |
| `s6170792c.mp4` | Pexels 6170792를 받은 뒤 `python -I tools/phone_comp.py <원본.mp4> public/s6170792c.mp4 <ffmpeg> <Pretendard-Regular.otf>`로 만든다 |
| `s5834558.mp4`, `s6868381.mp4`, `s6170612.mp4`, `s9520614.mp4`, `s19178495.mp4`, `s16420406.mp4` | Pexels 동영상 번호. 1080×1920 파일을 받아 이름만 바꾼다 |
| `music5.wav` | 배경음악(작업 폴더). 100BPM, 첫 킥이 0.08초, 후반부가 19.28초에서 시작한다 |

```
cd docs/demo/video/remotion
npm install
npx remotion render src/index.jsx NextStopShortV5 out-short5.mp4 --codec=h264 --crf=16 --audio-codec=aac --audio-bitrate=256k
ffmpeg -i out-short5.mp4 -vf "noise=c0s=5:c0f=t+u,format=yuv420p" -c:v libx264 -crf 17 -af "loudnorm=I=-14:TP=-1.5:LRA=11" -c:a aac -b:a 192k -map_metadata -1 -movflags +faststart nextstop-shortform-v5.1.mp4
```

- 컷 시점은 `Short5.jsx`의 `SHOTS`, 효과음 시점은 `SFX`에 있다. 음악을 바꾸면 박자(0.6초 격자)와 26.4초에서 4마디 되감는 자리를 다시 잰다.
- 에뮬레이터 녹화는 6배속이다. P는 녹화 15.8초, 문 열림은 20.2초(3D 문 21.8초), 문 닫힘은 25.0초, D와 다음 집은 29.2초다.
- 거치대 폰 합성은 손이 화면 아래를 가리는 2.0~2.3초를 쓰지 않는다(모서리를 잘못 잡는다). 그래서 첫 장면은 2.4초부터 쓴다.

## 실제 촬영 출처 (Pexels 라이선스, 표기 의무 없음)

Tima Miroshnichenko(6170792, 6170612), Tim Samuel(5834558), Kindel Media(6868381), Ron Lach(9520614), SN.CHE(19178495), Lil Sakib(16420406)
