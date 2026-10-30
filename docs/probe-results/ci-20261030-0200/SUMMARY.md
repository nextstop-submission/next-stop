# 실측 결과 (auto, 20261029-1652)

| 항목 | 결과 | 상세 |
|---|---|---|
| JDK | PASS | openjdk version "17.0.21" 2026-10-20 |
| Android SDK | PASS | /usr/local/lib/android/sdk |
| 에뮬레이터 | PASS | 이미 연결됨: emulator-5554 |
| 에뮬레이터 이미지 | INFO | ro.product.model=Connect on x86_64 emulator, ro.build.version.release=14, ro.build.version.sdk=34, ro.build.type=user, ro.build.display.id=RELEASE.release_connect_v2.1.0 test-keys |
| Pleos Vehicle Service | PASS | versionName=2.3.22 |
| Virtual Console 접속 | PASS | HTTP 200 |
| Virtual Console 자원 목록(분석용) | INFO | /vendor/three.legacy.min.js, /vendor/GLTFLoader.legacy.js, /vendor/RGBELoader.legacy.js |
| Virtual Console 요청 경로(분석용) | INFO |  |
| APK 설치 | PASS | Success |
| 권한 부여 | PASS | 5개 granted |
| 실측 화면 자동 실행 | PASS |  |
| Vehicle.initialize | PASS | ok |
| Door capability | PASS | access=READ_WRITE bounds=[Lai.pleos.playground.vehicle.constant.area.DoorValue;@481fd99=0..1, [Lai.pleos.playground.vehicle.constant.area.DoorValue;@3f3c85e=0..1, [Lai.pleos.playground.vehicle.constant.area.DoorValue;@c8… |
| Door 운전석 읽기 | PASS | 0 |
| 기어 GEAR_SELECTION 읽기 | PASS | 4 (PARK) |
| 기어 CURRENT_GEAR 읽기 | PASS | 4 (PARK) |
| AAOS PERF_ODOMETER 읽기 | INFO | fail SecurityException requires android.car.permission.CAR_MILEAGE |
| Pleos Odometer 누적거리(getCurrentDistanceDriven) | PASS | call.getCurrentDistanceDriven.cb0/0.0<Float> |
| Pleos Odometer 숫자 결과(전체) | PASS | call.getCurrentDistanceDriven.cb0/0.0<Float> / call.getRangeRemainingDistance.cb0/500000.0<Float> / update.registerDistanceDriven.onDistanceDrivenUpdated/0.0<Float> / update.registerRangeRemainingListener.onRangeRemainin… |
| CarUxRestrictions | PASS | false |
| NaviHelper.initialize | PASS | ok |
| requestRoute 호출(poiId 빈값) | PASS | called |
| requestRoute 결과 이벤트 | WARN | initialize.onConnected / Requested(stopId=s02) |
| 내비 최근 목적지 가져오기 | WARN |  |
| 내비 즐겨찾기 가져오기 | WARN |  |
| 주행 제한 ON → 앱 CarUxRestrictions 수신 | FAIL | cmd: Can't find service: hmg_car_service |
| 주행 제한 OFF → 앱 수신 | FAIL | cmd: Can't find service: hmg_car_service |
| adb root | INFO | adbd cannot run as root in production builds |
| VHAL 주입: 기어 D(시작) | FAIL |  |
| VHAL 주입: 기어 P | FAIL |  |
| VHAL 주입: 운전석 문 열림 | FAIL |  |
| VHAL 주입: 운전석 문 닫힘 | FAIL |  |
| VHAL 주입: 기어 D | FAIL |  |
| 시연 모드: 1건 완료 판정 | PASS | STOP_COMPLETED x2 (기대 2) |
| 시연 모드: 다음 배송지 내비 전달 | PASS | Requested x3 (기대 3) |
| 앱 크래시 | PASS | 없음 |

파일: demo-0-start.png, demo-1-ready.png, demo-2-transition.png, demo-3-next.png, demo-4-regulated.png, demo-logcat.txt, environment.txt, odometer-methods.txt, permissions.txt, probe-auto.png, probe-logcat.txt, regulation-on.png, vhal-inject.txt
