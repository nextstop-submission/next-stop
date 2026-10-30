# 실측 결과 (auto, 20261030-1051)

| 항목 | 결과 | 상세 |
|---|---|---|
| JDK | PASS | java version "21.0.12.1" 2026-08-18 LTS |
| Android SDK | PASS | <<<user>>-home>\AppData\Local\Android\Sdk |
| 에뮬레이터 | PASS | Pleos_Connect_v2_1_0 부팅 완료 153초 (-gpu host) |
| 에뮬레이터 이미지 | INFO | ro.product.model=Connect on x86_64 emulator, ro.build.version.release=14, ro.build.version.sdk=34, ro.build.type=<<user>>, ro.build.display.id=RELEASE.release_connect_v2.1.0 test-keys |
| Pleos Vehicle Service | PASS | versionName=2.3.22 |
| Virtual Console 접속 | PASS | HTTP 200 |
| Virtual Console 자원 목록(분석용) | INFO | /vendor/three.legacy.min.js, /vendor/GLTFLoader.legacy.js, /vendor/RGBELoader.legacy.js |
| APK 설치 | PASS | Success |
| 권한 부여 | WARN | android.car.permission.CAR_MILEAGE: granted=false; android.car.permission.CAR_DRIVING_STATE: granted=false |
| 실측 화면 자동 실행 | FAIL |  |
| Vehicle.initialize | FAIL |  |
| Door capability | FAIL |  |
| Door 운전석 읽기 | FAIL |  |
| 기어 GEAR_SELECTION 읽기 | FAIL |  |
| 기어 CURRENT_GEAR 읽기 | INFO |  |
| AAOS PERF_ODOMETER 읽기 | INFO |  |
| Pleos Odometer 누적거리(getCurrentDistanceDriven) | FAIL |  |
| Pleos Odometer 숫자 결과(전체) | FAIL |  |
| CarUxRestrictions | INFO |  |
| NaviHelper.initialize | FAIL |  |
| requestRoute 호출(poiId 빈값) | FAIL |  |
| requestRoute 결과 이벤트 | WARN |  |
| 내비 최근 목적지 가져오기 | WARN |  |
| 내비 즐겨찾기 가져오기 | WARN |  |
| 주행 제한 ON → 앱 CarUxRestrictions 수신 | FAIL | cmd: Can't find service: hmg_car_service |
| 주행 제한 OFF → 앱 수신 | FAIL | cmd: Can't find service: hmg_car_service |
| adb root | INFO | adbd cannot run as root in production builds |
| VHAL 주입: 기어 D(시작) | FAIL | System.Management.Automation.RemoteException |
| VHAL 주입: 기어 P | FAIL | System.Management.Automation.RemoteException |
| VHAL 주입: 운전석 문 열림 | FAIL | System.Management.Automation.RemoteException |
| VHAL 주입: 운전석 문 닫힘 | FAIL | System.Management.Automation.RemoteException |
| VHAL 주입: 기어 D | FAIL | System.Management.Automation.RemoteException |
| 시연 모드: 1건 완료 판정 | PASS | STOP_COMPLETED x2 (기대 2) |
| 시연 모드: 다음 배송지 내비 전달 | PASS | Requested x4 (기대 3) |
| 앱 크래시 | PASS | 없음 |

파일: demo-0-start.png, demo-1-ready.png, demo-2-transition.png, demo-3-next.png, demo-4-regulated.png, demo-logcat.txt, environment.txt, odometer-methods.txt, permissions.txt, probe-auto.png, probe-logcat.txt, regulation-on.png, vhal-inject.txt
