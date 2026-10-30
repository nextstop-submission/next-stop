# 숏폼 챌린지 영상 — `nextstop-shortform.mp4`

공모전 숏폼 챌린지(특별상)에 낼 세로 영상이다. 게시(Instagram 릴스 / YouTube Shorts)는 검토 뒤에 한다. 설명란 글과 해시태그는 [shortform-설명.txt](shortform-설명.txt)에 있다.

| 항목 | 값 |
|---|---|
| 형식 | 1080×1920, 60fps, H.264 + AAC 192k, 메타데이터 비움 |
| 길이 | 46.5초(공식 조건 30초~1분) |
| 구성 | 공식 예시 Spot → Solve → Scene |
| 소리 | 숏폼 전용 내레이션(`shortform_narration.mp3`, 42.4초)에 배경음악을 약 -22dB로 깔았다. 음악은 끝에서 페이드아웃한다 |
| 자막 | 화면에 구웠다. 음성의 '피'·'디'는 P·D로 적었다 |
| 표기 | AI 생성 실사 장면 2곳은 '연출 장면 (AI 생성)', 그래픽 장면은 '연출 장면'으로 표기했다. 앱 화면은 '실제 화면'(Pleos 공식 에뮬레이터) 또는 '앱 화면 렌더링'이다 |

## 장면(영상 초)

| 시간 | 구간 | 내레이션 | 화면 |
|---|---|---|---|
| 0~4 | SPOT | 운전하다 또 휴대폰. 다음 집이 어디였더라? | 실사: 차를 세운 배송 기사가 폰 목록을 넘긴다(AI 생성). 위에 "운전 중, 또 휴대폰." |
| 4~8.5 | SPOT | 하루 수십 집을 도는 택배 부업, 매번 이렇습니다. | 그래픽: 목록이 넘어가는 휴대폰, 알림, 말풍선 |
| 8.5~11.7 | SOLVE | 그래서 만들었습니다. 다음집. | 다음집 로고 |
| 11.7~25.5 | SOLVE | 차를 세우고, P. 문을 열고, 닫고, 다시 D. … 화면은 만지지 않습니다. | 실제 화면(폭 90%). '다시 D'에 실사 1.1초(주차장을 빠져나가는 차, AI 생성) → D 한 번 = ✓ 1건 완료 → 다음 집 → 내비 자동 전달 · 화면 터치 0번 |
| 25.5~33.8 | SCENE | 달리는 동안엔 딱 두 줄 … 지하주차장에서도 차량 신호로 정확하게. | 주행 중 두 줄 화면(실제) |
| 33.8~38.7 | SCENE | 운행이 끝나면, 오늘 진짜 번 돈까지 자동으로 정산. | 정산 화면(앱 화면 렌더링) |
| 38.7~46.5 | 마무리 | 손은 운전대에. 다음 집은, 차가 챙깁니다. | 로고 + #HMG차량용앱개발공모전 |

- 기어 실사 클립은 앞 0~3초(변속기 근접)를 쓰지 않는다. 글자가 뭉개져 AI 티가 나기 때문이다. 3.0초 이후만 쓴다.
- Pleos 내비가 경로를 그린 화면은 녹화에 없어서, 다음 집 화면 위에 '내비로 자동 전달' 문구를 띄웠다.
- 모든 장면이 움직이거나 천천히 확대되므로 1.5초 넘게 멈춘 화면이 없다.

## 다시 만들기

소스: [`docs/demo/video/remotion/src/Short.jsx`](../video/remotion/src/Short.jsx), 컴포지션 이름은 `NextStopShort`.
음성·음악·AI 클립은 외부 도구로 만든 파일이라 저장소에 넣지 않는다. 작업 폴더에서 `public/`으로 복사해 쓴다.

```
cd docs/demo/video/remotion
npm install
copy <작업 폴더>\audio\shortform_narration.mp3 public\ & copy <작업 폴더>\audio\music.mp3 public\
copy <작업 폴더>\video\ai_intro_driver.mp4 public\ & copy <작업 폴더>\video\ai_gear_PtoD.mp4 public\
npx remotion render src/index.jsx NextStopShort out-short.mp4 --props="{\"intro\":\"ai_intro_driver.mp4\",\"gear\":\"ai_gear_PtoD.mp4\",\"gearFrom\":3.0}" --codec=h264 --crf=18 --audio-codec=aac --audio-bitrate=192k
ffmpeg -i out-short.mp4 -map_metadata -1 -fflags +bitexact -c copy nextstop-shortform.mp4
```

- `--props`를 빼면 AI 클립 없이 그래픽만으로 만든다.
- Windows에서 경로가 길면 `--browser-executable`로 설치된 Edge나 Chrome을 지정한다.
- 내레이션을 바꾸면 `Short.jsx`의 `S`(장면 시작), `SUBS`(자막), `BEATS`(P·문·D 시점)를 새 음성의 쉼에 맞춰 다시 잰다.
  쉼은 `ffmpeg -i <파일> -af silencedetect=n=-25dB:d=0.25 -f null -`로 찾는다.
