# Pleos Connect Emulator

> **시연 경로 판정(10/30 최종 결정):** 노트북 대체안 A **실패**(멈춤 반복, 아래 'A 실측') →
> **본선 시연은 C** — GitHub Actions의 Pleos 공식 에뮬레이터에서 Virtual Console로 실제 차량 신호(AAOS 기어·Pleos Door)를 바꿔 자동 완료되는 실화면(아래 'GitHub Actions 실측', 6건 완료).
> 시연 영상·실측 증거는 C 녹화본과 로그로 내고, 12/3~4 현장에서는 C 녹화본을 재생하며 실측 기록을 함께 보인다(주최 측이 GPU PC를 주면 그 PC에서 Virtual Console 실연).
> **B(시연 신호 목업)는 예비** — 현장 PC가 없거나 C 재생이 막힐 때 노트북에서 시연 신호 모드로 흐름을 보여 준다.

출처: 「Pleos Connect Emulator 설치 및 실행」(`setup-connect-sdk-emulator`), 「Pleos Connect Emulator 설정」(`configure-pleos-connect-emulator`), 「Virtual Console 설정 가이드」(`use-vehicle-emulator`) — document.pleos.ai ✅ 원문 (2026-10-29 재확인).
업데이트 사이트 XML(`crp-sys-img.xml`) ✅ 원문 (2026-10-29 직접 받아 봄). 가속 조건: Android 공식 「Configure hardware acceleration」 ✅ 원문.

## 개발 노트북 사양 확인 (2026-10-29)

| 항목 | 공식 기준 | 개발 노트북 | 판정 |
|---|---|---|---|
| 그래픽 카드 | **필수**. 최소 RTX 3060 Laptop 6GB / RX 6600M·6700S 8GB | Intel Iris Xe(내장 그래픽, 시스템 메모리 공유) | **미달** |
| CPU | 가상화 지원(Intel VT-x·EPT) | i7-1165G7, 4코어 8스레드, VT-x·EPT 있음 | 충족 |
| 펌웨어 가상화 | BIOS에서 켜짐 | 켜짐 | 충족 |
| 하이퍼바이저 | WHPX(권장) 또는 AEHD | 10/29엔 둘 다 없었음 → 10/30 Windows 하이퍼바이저 플랫폼을 켜고 재부팅, `WHPX(10.0.26200) is installed and usable` | 충족(10/30) |
| RAM | 16GB 권장(Android 에뮬레이터), AVD 4GB | 16GB | 겨우 충족 |
| 디스크 | 16GB 이상 + 이미지 4.1GB | 여유 280GB 이상 | 충족 |
| Android Studio | 최신 | 2026.2.2 설치함(2026-10-29) | 충족 |

판정(사전에 정해 둔 규칙): **사양 미달**(그래픽 카드 필수 조건 불충족) → 대체 설계안 2개를 아래에 둔다. 개인 참가라 다른 노트북으로 바꿀 수는 없다.

## 대체안 A 실측 (2026-10-30, `-gpu host`, AVD RAM 4GB, Cold boot)

A의 판정 기준(10/30 위임문, 측정 전): 통과 = 부팅 성공 + Virtual Console로 기어·도어를 바꿀 때 앱이 반응.
실패 = `host`·`swiftshader_indirect` 둘 다 10분 안에 부팅하지 못함, **또는 신호 반응이 30초를 넘거나 멈춤이 반복됨** → B로 확정. *(기준 원문 그대로 둠. 10/30 C가 실제 신호 완료에 성공해 '본선 C, B 예비'로 바꿨다 — 맨 위 판정)*

| 항목 | 결과 | 근거(`docs/probe-results/20261030-*`) |
|---|---|---|
| 부팅 | ✅ 3번 모두 부팅. 153초 / 209초 / 166초, 홈 화면까지 +50초 | auto-host `SUMMARY.md`, 콘솔 기록 |
| 앱 설치·실행 | ✅ 설치되고 주행 화면이 정상으로 그려짐 | auto-host `demo-1-ready.png` |
| Pleos Vehicle | ✅ `Vehicle.initialize` ok, Door capability `READ_WRITE`, 운전석 문 읽기 0, 기어 P(4) 수신 | auto-retry `probe-logcat.txt` |
| Pleos Odometer | ✅ `getCurrentDistanceDriven` 0.0(READ), 구독 동작. 트립 거리·시간 API는 `ERROR_NOT_SUPPORTED_PROPERTY` | auto-retry |
| NaviHelper | ✅ initialize ok → onConnected, `requestRoute`(poiId `""`·`"0"`) 호출 성공 → **Pleos 내비가 앞으로 나와 목적지 '다솜동 3구역'을 띄움**. 지도는 그려지지 않고 경로 계산이 도는 화면에서 멈춤 | auto-host `demo-3-next.png` |
| 시연 신호 모드(adb) | ✅ 2건 완료(`STOP_COMPLETED` x2), 다음 배송지 내비 요청 x4 | auto-host `demo-logcat.txt` |
| Virtual Console 기어 D → 앱 | ⚠️ 도착했지만 **28.0초** 걸림(콘솔 서버 기록 시각 → 앱 로그, 둘 다 기기 시계) | vc-host `vc-logcat.txt` |
| Virtual Console 앞 왼쪽 문 열림 → 앱 | ⚠️ Pleos Door가 `ROW_1_LEFT=1` 수신. 앱 메인 스레드는 12.6초 늦게 처리(기어 D 상태라 `DOOR_WHILE_MOVING`으로 정상 무시) | vc-host |
| Virtual Console Odometer 3km → 앱 | ⚠️ 3.0 수신. 앱 처리까지 약 21초 지연 | vc-host |
| 안정성 | ❌ **두 번 띄워 두 번 다 부팅 5~8분 뒤 Pleos 차량 HAL 서비스(`vehicle_ext`·`hardware.profile`·`adas`·`diagnostic` 등)가 함께 abort → system_server 재시작 → 부팅 애니메이션에서 10분 넘게 못 돌아옴**. 두 번째는 `-no-audio`, 화면 녹화 없이 띄웠는데도 같았다. 노트북 CPU 사용률은 내내 100% | vc-host `crash-head.txt`, vc-host-noaudio `crash-cmdlines.txt` |
| Virtual Console 제어 API | ❌ 두 번째 부팅에선 `emulator_active=false`, `bridge_ready=false`로 6번 중 4번이 HTTP 503, 앱까지 간 신호 0 | vc-host-noaudio `vc-latency.md` |
| VHAL 직접 주입 | ❌ `inject-vhal-event requires non-user build`(배포용 빌드라 막힘), `adb root` 불가 | auto-host `vhal-inject.txt` |
| 화면 녹화 | ⚠️ 측정 중 녹화는 시스템 재시작 때 저장소(FUSE)가 끊겨 잃음. 대신 Cold boot 직후 B안 흐름 1건을 녹화해 PC로 꺼냄(42.5초) | b-demo `b-demo-2.mp4` |

**판정: A 실패(멈춤 반복).** → 본선 시연은 C(GitHub Actions 실측), B(목업)는 예비(맨 위 '시연 경로 판정').
`swiftshader_indirect`는 시도하지 않았다. CPU로 화면을 그리는 방식이라, 이미 CPU 100%에서 멈추는 이 노트북에서는 나아질 근거가 없다.

덧붙여 둘 사실: 문 신호(Door)와 내비 연동(NaviHelper)은 **Pleos 에뮬레이터에서 실제로 동작했다**. 그러니 앱 설계(문·기어·내비 구독)는 그대로 두고, 바꾸는 것은 시연 방식뿐이다.
노트북에서도 실차 SDK 연결은 확인됐고(위 표·스크린샷), 본선 시연은 같은 공식 이미지를 GitHub Actions에서 띄운 C 실화면으로 한다.

### 대체안 A — 저사양 모드(같은 Pleos 에뮬레이터, 소프트웨어 렌더링)

WHPX를 켜고 같은 AVD를 GPU 없이 띄운다.

```
emulator -avd <AVD 이름> -no-snapshot-load -gpu swiftshader_indirect -no-audio
```
(`-gpu host`(Iris Xe 하드웨어 렌더링)를 먼저 시도하고, 화면이 깨지거나 멈추면 `swiftshader_indirect`로 바꾼다.)

| 장점 | 단점 |
|---|---|
| 진짜 Pleos 이미지, VHAL, Virtual Console을 그대로 쓴다 → 도어·기어·Odometer·NaviHelper 실측과 실시연이 가능하다 | Pleos가 "그래픽 카드 필수"라고 밝힌 조건을 벗어난다 — 부팅이 안 되거나, 내비 지도 화면이 아주 느릴 수 있다 |
| 코드·시연 대본을 바꿀 필요가 없다 | WHPX 켜기와 재부팅이 필요하다(시스템 설정 — 사람 손) |
| 녹화본을 만들어 두면 PT 때 화면이 느려도 녹화본으로 대체할 수 있다 | 2560×1440 화면을 CPU로 그리므로 실시연에서는 끊김을 감수해야 한다 |

### 대체안 B — Pleos 에뮬레이터 없이 시연 신호로 시연(SDK 목업)

Pleos SDK가 들어갈 자리를 앱에 이미 있는 시연 신호 경로(`BroadcastSignalSource`, 화면 시연 패널, `scripts/demo/demo.ps1 -Mode broadcast`)로 채워 흐름을 보여 준다.
돌리는 기기는 다음 중 하나다.
- 구글 기본 Android Automotive 에뮬레이터(API 34) — 이것도 WHPX가 필요하지만 Pleos 내비가 없어 가볍다.
- 안드로이드 폰·태블릿 — 매니페스트의 `android.hardware.type.automotive`를 `required="false"`로 바꾼 시연용 빌드가 필요하다(아직 만들지 않음).

| 장점 | 단점 |
|---|---|
| Pleos 그래픽 조건과 상관없다 → 이 노트북에서 확실하게 돌아간다 | 실제 Pleos 도어·내비 신호를 보여 줄 수 없다 → 기술 활용성 점수를 잃을 수 있다 |
| 코드가 이미 있다(시연 패널, adb 신호) | 내비 연동은 "앱 안에서 안내하는 화면"으로만 보여 줄 수 있다 |
| 핵심 장면(P → 문 → D → 다음 집)은 그대로 재현된다 | 발표에서 "실차에서는 Door/NaviHelper를 구독한다"는 설명과 코드(`platform/Pleos*`)로 근거를 보완해야 한다 |

권장(10/29): A를 먼저 시도하고, A가 안 되면 B로 간다. → **10/30 실측: A 실패(위 표). 그 사이 C(GitHub Actions)가 실제 신호 완료에 성공해 본선은 C, B는 예비로 정했다.**
예비 B의 기기는 **이 Pleos 에뮬레이터 그대로**도 된다. 10/30 시연 신호 모드 2건이 멈추기 전 몇 분 안에 끝났다. 다만 5~8분 뒤 멈출 수 있으니 시연은 녹화본을 주로 쓰고, 녹화는 Cold boot 직후 몇 분 안에 짧게 끊어서 한다.

## GitHub Actions 실측 (대체안 C, 2026-10-30)

노트북과 별개로, GitHub 호스트 러너(Linux, 2코어·7GB·KVM)에서 같은 공식 이미지를 창 없이·소프트웨어 렌더링으로 띄워
노트북과 같은 실측 스크립트를 돌렸다(`.github/workflows/emulator.yml`). 결과 원본: `docs/probe-results/ci-20261030-0023/` 🔍 실측.

| 확인한 것 | 결과 |
|---|---|
| 부팅 | cold boot 60초 만에 `sys.boot_completed=1`. 빌드 `RELEASE.release_connect_v2.1.0`(**user** 빌드, test-keys), 2560×1440, 160dpi, Vehicle Service 2.3.22 |
| 이미지 크기 | zip 4.09GB, 풀면 약 19.6GB(system.img 18.3GB) — 빈 영역을 sparse로 바꾸면 실제 사용 5.9GB |
| Pleos Vehicle | `initialize` 성공. Door 6개 영역(앞·뒤 좌우, 후드, 트렁크) 초기값 수신, capability `READ_WRITE 0..1`, 운전석 0(닫힘). Odometer `getCurrentDistanceDriven` 0.0km, 주행가능거리 500,000km |
| AAOS | `GEAR_SELECTION`·`CURRENT_GEAR` 읽기·구독 4(P). `CarUxRestrictions` false(정차) — `CAR_DRIVING_STATE` 없이 읽힘 |
| 권한 | Pleos 4개·`CAR_POWERTRAIN` 부여. **`CAR_MILEAGE`·`CAR_DRIVING_STATE`는 서드파티에 부여 안 됨** → 매니페스트에서 뺌, PERF_ODOMETER 경로 제외 |
| NaviHelper | `initialize` onConnected, 최근 목적지 0건·즐겨찾기 2건, `requestRoute` 호출 성공. 안내 시작 이벤트는 없음(내비 상태 FREE_DRIVE, 지도 '불러오는 중') |
| VHAL 주입 | **불가** — `inject-vhal-event requires non-user build`. 이 이미지에서 신호를 바꾸는 길은 Virtual Console뿐 |
| 주행 제한 강제 | **불가** — `cmd hmg_car_service`가 이 이미지에 없음(문서의 명령은 실차·개발 장비용으로 보인다) |
| Virtual Console | 8088 포트 HTTP 200. 3D 차량(three.js) 웹 화면 → CI는 `scripts/probe/vc-drive.mjs`로 사람처럼 눌러 조작한다. **실제 신호로 3건 완료 PASS**(`ci-20261030-0347/vc/vc-app-logcat.txt`): VC의 기어·운전석 문 → AAOS `GEAR_SELECTION`·Pleos Door → `STOP_COMPLETED trigger=GEAR_DRIVE doorCycles=1` ×3. 녹화 `ci-20261030-0347/vc-drive-clean.mp4`(왼쪽 Pleos 차량 패널의 문 열림 그림과 앱 진행이 함께 보인다) |
| 안정성 | 10/30 실행 5회: 4회는 60초 만에 부팅, 1회는 30분 안에 부팅하지 못함(러너 편차 — 다시 실행). 부팅한 4회 중 1회는 실측 도중 `car_service`가 죽었다(네이티브 크래시, 러너 메모리 7GB에 AVD 4GB). 워크플로는 핵심인 Virtual Console 조작을 부팅 직후에 먼저 하고, 단계마다 서비스가 살아 있는지 보고 죽었으면 재부팅한다 |
| 화면 | 우리 앱은 오른쪽 앱 영역(왼쪽은 차량·미디어 패널)에 뜬다. **`requestRoute`를 보내면 내비(`ai.umos.maps.android.navigation.app`)가 앞으로 나와 앱을 덮고**, 그동안 뒤에 있던 앱 프로세스가 약 1분 뒤 종료됐다(PID 9423 → 31785) → 완료 알림 한 줄(`CompletionNotifier`)과 운행 중 포그라운드 서비스(`RunService`)로 대응. 다음 실행(`ci-20261030-0418`)에서 `RunService` 포그라운드 기동 확인, 한 프로세스로 6건 완료. 이 회차에서는 내비가 앞으로 나오지 않아 '뒤에 있을 때 종료되지 않는가'는 재현되지 않았다(노트북 실측에서 다시 본다) |

## 요구 사항

| 항목 | 값 |
|---|---|
| 앱 minSdk | API 28 이상(서명 키 로테이션 등 차량 보안 요구) — 이 앱은 33 |
| Gradle | 8.0 이상 — 이 앱은 8.10.2 |
| 언어 | Java, Kotlin |
| 에뮬레이터 | Pleos Connect Emulator **v2.1.0** (2026-10-19 배포, Virtual Console은 v2.1.0 이상) |
| **그래픽 카드** | 원문: "그래픽 카드가 필수". 권장 NVIDIA RTX 4060/5060 Laptop 8GB 이상, AMD RX 7700S/RX 7600M XT 8GB급 이상 외장 GPU. **최소 RTX 3060 Laptop 6GB 이상, RX 6600M 8GB 또는 RX 6700S 8GB 이상** (출처: 「Pleos Connect Emulator 설정」) |
| **하이퍼바이저**(Windows) | x86_64 이미지라 VM 가속이 필요하다. **WHPX 권장**(Windows 기능 > Windows 하이퍼바이저 플랫폼). AEHD는 2026-12-31 지원 종료 예정, HAXM은 에뮬레이터 36.2부터 사용 불가 (Android 공식 문서) |
| 시스템 이미지 | `system-images;android-34;pleos_car_emulator;x86_64` rev 2, 표시 이름 "Pleos Connect v2.1.0 APIs", 압축 파일 4.09GB, Android Emulator rev 24 이상 필요 (업데이트 사이트 XML) |
| 라이선스 | 이미지 설치 때 42dot「Software Development Kit License Agreement」에 동의해야 한다. 3.1.1항: 사전 통지 후 SDK 사용료를 요구할 수 있다는 조항이 있다 |

## 설치

1. 최신 Android Studio. 이전 Pleos 에뮬레이터가 있으면 먼저 제거.
2. (Windows) 가속 확인: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator -accel-check` → `WHPX ... is installed and usable`이 나와야 한다.
   없으면 시작 메뉴 "Windows 기능 켜기/끄기" → **Windows 하이퍼바이저 플랫폼** 체크 → 확인 → 재부팅.
3. SDK Manager → **SDK Update Sites** → `+`
   - Name: `Pleos Connect System Image`
   - Windows/Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - Mac: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
4. SDK Platforms 탭 → Show Package Details → **Android API 34의 Pleos Connect 시스템 이미지** 설치(라이선스 동의) → Android Studio 재시작.
5. Device Manager → Create device → Form Factor **Automotive** → **Pleos Connect v2.1.0** → Advanced Settings:
   - Startup > Default boot: **Cold** (Quick Boot면 VHAL 사용 불가)
   - RAM 4GB
   - (구버전 Android Studio) Graphics: Hardware
6. API 34 / Pleos Connect v2.1.0 APIs 선택 → Finish.

## 운영 메모

- 기본 언어는 한국어(차량 설정 > 일반 설정 > 언어/Language에서 변경).
- 마이크(STT 시험용): Extended Controls > Microphone에서 오디오 입력 허용.
- 2.1.0에서 Bluetooth 동글 연동·LLM SDK·CRN 지원 종료.
- 에뮬레이터 코덱은 실차와 다를 수 있다(시연 영상은 PC에서 녹화본 재생 권장).
- 공식 샘플 스크린샷 환경: `Pleos_Connect_v2.1`(Android 14), 2560×1440, Vehicle Service 2.3.22 ✅ 샘플.

## 알려진 위험

- 차 위치가 움직이지 않는다(타 참가팀 실측 보고) → 도착 이벤트에 기대지 않는다. 주행거리는 Virtual Console Odometer로 시연.
- Quick Boot로 켜면 VHAL 비활성 → 기어·도어 값이 안 바뀐다.
- 그래픽 카드가 최소 사양 미만이면(내장 그래픽 포함) 에뮬레이터 실행 자체가 어렵다 → 위 '개발 노트북 사양 확인'과 대체안 A·B.
- 내장 그래픽 노트북(i7-1165G7·Iris Xe·16GB)에서는 부팅은 되지만, 5~8분 뒤 Pleos 차량 HAL 서비스가 abort하고 시스템이 재시작된다(10/30 실측 2회 중 2회). 신호도 12~28초 늦게 온다.
- 배포용(user) 빌드라 `cmd car_service inject-vhal-event`와 `adb root`가 막혀 있다 ✅ 실측(10/30) → 기어·도어는 Virtual Console로만 바꿀 수 있다.
- 하이퍼바이저가 없으면 x86_64 이미지는 사실상 못 띄운다(가속 없는 x86_64 실행은 막히거나 매우 느리다) → 설치 2단계.
