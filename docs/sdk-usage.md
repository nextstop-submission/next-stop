# SDK·API 사용 내역

접수양식 5번(개발 계획)의 'SDK/API 사용계획'에 그대로 옮겨 쓸 수 있도록 **실제 코드에서 호출하는 것만** 적는다.
상태: ✅ 코드 반영 · 🟢 Pleos 공식 에뮬레이터 이미지 v2.1.0에서 실측 통과(근거 폴더는 칸에, 전체 색인 [`docs/probe-results/INDEX.md`](probe-results/INDEX.md)) · ⏳ 에뮬레이터로 확인 불가

## 1. Pleos Vehicle SDK (`ai.pleos.playground:Vehicle:2.3.2`)

| 클래스 / 메서드 | 쓰는 곳 | 목적 | 상태 |
|---|---|---|---|
| `Vehicle(context)`, `initialize()`, `release()` | `platform/PleosVehicleSession.kt` | SDK 세션 수명주기 | 🟢 |
| `Vehicle.getDoor()` | `PleosDoorSource`, `ProbeActivity` | Door 도메인 | 🟢 |
| `Door.getCurrentDoorPosition(area, onComplete, onFailed)` | `PleosDoorSource` | 시작 시·P 진입 때·2초마다 1열 문 상태 다시 읽기(콜백 유실 대비) | 🟢 `ci-20261105-*` 리허설 |
| `Door.registerDoorPosition(area, DoorPositionListener)` / `unregisterDoorPosition` | `PleosDoorSource` | **배송 체크포인트: 1열 문 열림→닫힘**(운전석·조수석 묶음, 설정으로 운전석만) | 🟢 Virtual Console 리허설 완료 기록(`scripts/demo/rehearsal_stats.py`로 집계) |
| `Door.checkDoorPositionCapabilities(onComplete, onFailed)` | `ProbeActivity` | 지원 여부·값 범위 실측 | 🟢 READ_WRITE, 0..1 |
| `DoorArea(DoorValue.ROW_1_LEFT / ROW_1_RIGHT)` | `PleosDoorSource` | 1열 운전석·조수석 | 🟢 |
| `Vehicle.getOdometer()` | `PleosOdometerSource`, `ProbeActivity` | Odometer 도메인 | 🟢 |
| `Odometer.getCurrentDistanceDriven(onComplete, onFailed)` | `PleosOdometerSource` | 운행 시작 계기판 거리 | 🟢 (에뮬레이터 초기값 0.0km) |
| `Odometer.registerDistanceDriven(DistanceDrivenListener)` / `unregisterDistanceDriven` | `PleosOdometerSource` | **실주행거리 정산·운행기록**, 앱 재시작 중 놓친 출발(D) 보정(0.5km 이상 증가) | 🟢 리허설 정산 화면 |
| `Vehicle.getFuel()` → `Fuel.getCurrentFuelEconomy` / `getCumulativeFuelEconomy`(onComplete, onFailed) | `platform/PleosEnergyReader.kt` | **차가 계산한 평균 연비(km/L)로 연료비 정산** — 이번 주행 평균을 먼저, 없으면 누적 평균. 3~40km/L 밖이면 버리고 사용자 값(`core/settle/Efficiency.kt`) | ⏳ 공식 에뮬레이터 v2.1.0은 미지원(`ERROR_NOT_SUPPORTED_PROPERTY`, 11/7 리허설 `ci-20261107-*/rehearsal/c-app.txt`의 `NS|ENERGY`) → 입력값으로 정산되는 것까지 확인 |
| `Vehicle.getEvBattery()` → `EvBattery.getCurrentEvEfficiency` / `getCumulativeEvEfficiency` | `platform/PleosEnergyReader.kt` | 전기차는 평균 전비(km/kWh)로 충전비 정산(2~10km/kWh 밖이면 버림) | ⏳ 공식 에뮬레이터 v2.1.0은 미지원(`ERROR_NOT_SUPPORTED_PROPERTY`, 11/7 리허설 `ci-20261107-*/rehearsal/c-app.txt`의 `NS|ENERGY`) → 입력값으로 정산되는 것까지 확인 |

권한(SDK `@RequiresPermission`·공식 권한 표로 확인, 쓰는 도메인만): `pleos.car.permission.CAR_DOORS`(Door), `pleos.car.permission.CAR_DRIVE`(Odometer), `pleos.car.permission.CAR_ENERGY`(Fuel·EvBattery)

## 2. Pleos NaviHelper SDK (`ai.pleos.playground:NaviHelper:3.0.5`)

| 클래스 / 메서드 | 쓰는 곳 | 목적 | 상태 |
|---|---|---|---|
| `NaviHelper(context)`, `initialize(onConnected, onFailed)`, `addListener()`, `removeListener()`, `release()` | `platform/PleosNavigator.kt` | 수명주기(공식 문서 권장 콜백형 초기화, 연결 전 요청은 대기열) | 🟢 onConnected |
| `requestRoute(RouteInfo(latitude, longitude, poiId, poiName, poiSubId, address))` | `PleosNavigator.navigateTo` | **다음 배송지 1곳을 내비 목적지로 전달**(완료마다 교체, 가져온 배송지는 원래 POI ID 사용) | 🟢 내비 화면에 목적지 표시, `onRouteStarted` 수신(`ci-20261102-1505-navi8`) |
| `cancelRoute()` | 마지막 배송 완료 시 | 안내 종료 | ✅ |
| `getRecentDestinationInfo()` → `onRecentDestinationInfo(List<RecentDestinationInfo>)` | `PleosNavigator`, 계획 화면 | **배송지 입력: 내비 최근 목적지를 배송지로 가져오기**(이름·좌표·POI ID) | 🟢 (0건) |
| `getBookmarkInfo()` → `onBookmarkInfo(List<BookmarkInfo>)` | `PleosNavigator`, 계획 화면 | 배송지 입력: 내비 즐겨찾기 가져오기 | 🟢 (2건) |
| `requestNavigationScreen(SearchPlace)` → `onRouteStarted`·`onDestinationChanged` → `getDestinationInfo()` | `PleosNavigator`, `RunStore` | **배송지 직접 추가**: 내비 장소 검색을 열고, 사용자가 고른 목적지를 배송지로 받기 | 🟢 8회차에서 추가 확인(`ci-20261102-1505-navi8`) |
| `getRouteStateInfo()`, `getCurrentLocationInfo()` | `ProbeActivity`(debug 빌드만) | 실측 | 🟢 |
| `NaviHelperEventListener.onRoutingCompleted / onRouteStarted / onRouteCancelled / onError / onDestinationArrived` | `PleosNavigator` | 전달 결과 표시·로그 | 🟢 onRouteStarted · ⏳ onDestinationArrived(에뮬레이터가 위치 이동을 내비에 반영하지 않음 — 완료 판정은 P·문·D라 무관) |

권한: `pleos.car.permission.NAVI_ROUTE`(get*Info), `pleos.car.permission.NAVI_ROUTE_SEARCH`(requestRoute·cancelRoute), `pleos.car.permission.NAVI_CUSTOM_MAP`(requestNavigationScreen) — 공식 권한 표·SDK 주석으로 확인

## 3. Android Automotive Car API (`useLibrary("android.car")`)

기어는 Pleos SDK의 안내(Gear API deprecated → GEAR_SELECTION)에 따라 표준 AAOS API를 쓴다.

| 클래스 / 메서드 | 쓰는 곳 | 목적 | 상태 |
|---|---|---|---|
| `Car.createCar()`, `getCarManager(Car.PROPERTY_SERVICE)` | `platform/CarApi.kt` | Car 서비스 연결 | 🟢 |
| `CarPropertyManager.registerCallback(cb, VehiclePropertyIds.GEAR_SELECTION, SENSOR_RATE_ONCHANGE)` | `CarGearSource` | **배송 체크포인트: 기어 P / D** (Pleos SDK 2.3.2가 Gear API를 deprecated 처리하며 GEAR_SELECTION 사용을 안내) | 🟢 등록·초기값(P) · P↔D 변화 수신(Virtual Console) |
| `CarPropertyManager.getProperty(GEAR_SELECTION)` | `CarGearSource` | 2초마다 직접 읽기(콜백 유실 보정, VHAL 시각으로 옛 값 거름), 연결이 끊기면 2초마다 다시 붙음 | 🟢 리허설에서 콜백보다 먼저 잡은 D 기록 |
| `VehiclePropertyIds.PERF_ODOMETER` | (뺌) | 주행거리 대체 경로였음 | ❌ Pleos 이미지에서 `CAR_MILEAGE` 미부여 → 운행 원천에서 제외 |
| `CarUxRestrictionsManager.registerListener` / `isRequiresDistractionOptimization` | `UxRestrictionWatcher` | **주행 중 2줄 화면**, 제한 중에는 움직이는 연출 금지(정적 확인) | 🟢 false(정차) |
| 매니페스트 `distractionOptimized=true` | `DriveActivity` | 주행 중 표시 허용 — 대신 제한 중엔 어떤 화면이든 2줄·버튼 0(`DrivingRestrictionTest`) | 🟢 |

권한: `android.car.permission.CAR_POWERTRAIN`만. `CAR_MILEAGE`·`CAR_DRIVING_STATE`는 Pleos 이미지에서 서드파티에 부여되지 않아 뺐다(실측 2026-10-30). 주행 제한 상태는 권한 없이 읽혔다

## 4. Android 기본 API

| API | 목적 | 비고 |
|---|---|---|
| `android.speech.tts.TextToSpeech` | (선택) "3건 완료. 다음, ○○" 음성 안내 | **기본 꺼짐**, 계획 화면에서 켬. 시연 핵심 흐름은 화면만으로 완결. Pleos TTS SDK에는 의존하지 않음 |
| `BroadcastReceiver`(`app.nextstop.SIGNAL`) | 시연·테스트용 목 신호 | 시연 모드에서만 등록, 보내는 쪽에 `android.permission.DUMP` 요구(adb만 가능) |
| `Settings.Global.BOOT_COUNT` | 정차 중 헤드유닛 재시작 판별(시동 끔) | 값이 바뀌었으면 꺼진 동안 못 받은 문 신호를 1회로 |
| `NotificationManager`(채널 중요도 LOW — 헤드업·소리·진동 없음) · 권한 `POST_NOTIFICATIONS` | 내비가 앱을 덮은 동안 1건 완료를 상태 표시줄 한 줄로(내비를 가리지 않음) | 알림 ID 1 완료 · 2 운행 중 · 3 배송지 추가 |

## 5. 쓰지 않는 것과 이유

Vehicle SDK 2.3.2의 다른 도메인(Seat·Light·트렁크 등)도 판정 보조로 검토했다.

| 신호 | 판정에 | 이유 |
|---|---|---|
| Door 2열·트렁크 | 안 씀 | 짐 정리·승객 하차로도 열려 비배송 정차 오탐이 늘어난다 |
| Seat 착좌(`SeatOccupancyListener`) | 안 씀 | 편의점·화장실처럼 걸러야 할 정차도 실제로 내리는 정차라 착좌 해제가 똑같이 일어나 판별력이 없고, 필수로 하면 동승자 배송을 놓친다 |
| Seat 안전벨트 | 안 씀 | 착좌와 같은 이유, 단거리 배송자는 벨트를 자주 매지 않아 신호가 흔들린다 |
| Light 비상등 | 안 씀 | 배송·비배송 정차 모두 켜는 습관이 사람마다 달라 판별력이 없다 |
| Gear(Pleos) | 안 씀 | 2.3.2에서 deprecated, 경고가 AAOS GEAR_SELECTION을 안내 |


| 항목 | 이유 |
|---|---|
| 외부 경로·지도 API(키 필요) | 공모전 규정 확인 전, 시연장 네트워크·비용 위험. 직선거리×보정계수로 동선 최적화 |
| `onDestinationArrived`를 완료 기준으로 | 에뮬레이터 위치가 안 움직임. 완료는 차량 신호(P·문·D)로 판정 |
| `requestRouteWithWaypoints` / `addWaypoint` | 경유지 수 제한. 1곳씩 교체가 배송 흐름에 맞음 |
| Pleos TextToSpeech·SpeechToText 3.3.1 | 둘 다 `registerApp(clientID, clientSecret, …)`로 앱 등록 비밀값을 넣어야 한다(공식 Maven aar의 `TextToSpeech` 클래스 javap: 메서드 `registerApp`, 인자 이름 문자열 `clientID`·`clientSecret`). 공개 저장소에 비밀값을 둘 수 없고 심사위원이 같은 빌드를 재현할 수 없어 이번 제출에서 뺐다 — 음성 안내는 Android 기본 TTS(선택 기능). 운영사에 심사용 등록 방법을 확인한 뒤 붙인다 |
| Pleos FusedLocation | 위치를 쓰지 않는 설계(지하·단지에서도 같은 동작, 위치 권한 0) |
| 배송 플랫폼 앱 연동·자동화 | 규정·약관 위험. 배송지 입력은 앱 안에서 |

## 6. Pleos 설계 가이드 준수 (원문 대조 2026-10-29)

| 가이드 | 앱 |
|---|---|
| 앱은 플랫폼 CarUxRestrictions에 따라 주행 중 UX를 제한 | UX 제한 또는 기어 P 아님 → 2줄 화면, 버튼 숨김 |
| 주행 중 움직이는 이미지·자동 스크롤로 시선 유도 금지 | 완료 연출을 없앴다: 같은 카드의 글자·진행 막대만 바뀌고 화면 전환 0회, 다음 목적지는 D 순간 바로 |
| 1~2초 안에 파악할 핵심 정보만 | 주행 화면 정보 2줄(다음 배송지·남은 건수) |
| 쓰는 도메인 권한만 선언 | Pleos 권한 5개(CAR_DOORS, CAR_DRIVE, NAVI_ROUTE, NAVI_ROUTE_SEARCH, NAVI_CUSTOM_MAP) + AAOS CAR_POWERTRAIN |
| 개인·위치 정보 사용 시 약관·처리방침 | 위치 권한 없음, 서버 전송 없음, 실측 로그에 좌표·주소 미기록, '새 운행' 때 배송지 이름·좌표 삭제 |

