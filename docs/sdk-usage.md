# SDK·API 사용 내역

접수양식 5번(개발 계획)의 'SDK/API 사용계획'에 그대로 옮겨 쓸 수 있도록 **실제 코드에서 호출하는 것만** 적는다.
상태: ✅ 코드 반영 · 🟢 Pleos 공식 에뮬레이터 이미지 v2.1.0에서 실측 통과(GitHub Actions, `docs/probe-results/ci-20261030-0023`) · 🔍 에뮬레이터 실측 대기 · ⏳ 실측 후 확정

## 1. Pleos Vehicle SDK (`ai.pleos.playground:Vehicle:2.3.2`)

| 클래스 / 메서드 | 쓰는 곳 | 목적 | 상태 |
|---|---|---|---|
| `Vehicle(context)`, `initialize()`, `release()` | `platform/PleosVehicleSession.kt` | SDK 세션 수명주기 | 🟢 |
| `Vehicle.getDoor()` | `PleosDoorSource`, `ProbeActivity` | Door 도메인 | 🟢 |
| `Door.getCurrentDoorPosition(area, onComplete, onFailed)` | `PleosDoorSource` | 시작 시 운전석 도어 상태 | 🟢 (운전석 0=닫힘) |
| `Door.registerDoorPosition(area, DoorPositionListener)` / `unregisterDoorPosition` | `PleosDoorSource` | **배송 체크포인트: 문 열림→닫힘** | 🟢 등록·초기값 수신(6개 영역) · 열림·닫힘 변화 수신(Virtual Console, 3건 완료) |
| `Door.checkDoorPositionCapabilities(onComplete, onFailed)` | `ProbeActivity` | 지원 여부·값 범위 실측 | 🟢 READ_WRITE, 0..1 |
| `DoorArea(DoorValue.ROW_1_LEFT)` | `PleosDoorSource` | 운전석 도어 영역 | ✅ |
| `Vehicle.getOdometer()` | `PleosOdometerSource`, `ProbeActivity` | Odometer 도메인 | 🟢 |
| `Odometer.getCurrentDistanceDriven(onComplete, onFailed)` | `PleosOdometerSource` | 운행 시작 계기판 거리 | 🟢 (에뮬레이터 초기값 0.0km) |
| `Odometer.registerDistanceDriven(DistanceDrivenListener)` / `unregisterDistanceDriven` | `PleosOdometerSource` | **실주행거리 정산·운행기록** | 🟢 주기 갱신 수신 |

권한(SDK `@RequiresPermission`·공식 권한 표로 확인, 쓰는 도메인만): `pleos.car.permission.CAR_DOORS`(Door), `pleos.car.permission.CAR_DRIVE`(Odometer)

## 2. Pleos NaviHelper SDK (`ai.pleos.playground:NaviHelper:3.0.5`)

| 클래스 / 메서드 | 쓰는 곳 | 목적 | 상태 |
|---|---|---|---|
| `NaviHelper(context)`, `initialize(onConnected, onFailed)`, `addListener()`, `removeListener()`, `release()` | `platform/PleosNavigator.kt` | 수명주기(공식 문서 권장 콜백형 초기화, 연결 전 요청은 대기열) | 🟢 onConnected |
| `requestRoute(RouteInfo(latitude, longitude, poiId, poiName, poiSubId, address))` | `PleosNavigator.navigateTo` | **다음 배송지 1곳을 내비 목적지로 전달**(완료마다 교체, 가져온 배송지는 원래 POI ID 사용) | 🟢 호출 / ⏳ 경로 안내 시작 이벤트 없음(내비 FREE_DRIVE, 소프트웨어 렌더링에서 지도 로딩 중) |
| `cancelRoute()` | 마지막 배송 완료 시 | 안내 종료 | ✅ 🔍 |
| `getRecentDestinationInfo()` → `onRecentDestinationInfo(List<RecentDestinationInfo>)` | `PleosNavigator`, 계획 화면 | **배송지 입력: 내비 최근 목적지를 배송지로 가져오기**(이름·좌표·POI ID) | 🟢 (0건) |
| `getBookmarkInfo()` → `onBookmarkInfo(List<BookmarkInfo>)` | `PleosNavigator`, 계획 화면 | 배송지 입력: 내비 즐겨찾기 가져오기 | 🟢 (2건) |
| `getDestinationInfo()`, `getRouteStateInfo()`, `getCurrentLocationInfo()` | `ProbeActivity` | 실측(목적지 반영 확인) | 🟢 |
| `NaviHelperEventListener.onRoutingCompleted / onRouteStarted / onRouteCancelled / onError / onDestinationArrived` | `PleosNavigator` | 전달 결과 표시·로그 | ✅ 🔍 |

권한: `pleos.car.permission.NAVI_ROUTE`(get*Info), `pleos.car.permission.NAVI_ROUTE_SEARCH`(requestRoute·cancelRoute) — 공식 권한 표·SDK 주석으로 확인

## 3. Android Automotive Car API (`useLibrary("android.car")`)

기어는 Pleos SDK의 안내(Gear API deprecated → GEAR_SELECTION)에 따라 표준 AAOS API를 쓴다.

| 클래스 / 메서드 | 쓰는 곳 | 목적 | 상태 |
|---|---|---|---|
| `Car.createCar()`, `getCarManager(Car.PROPERTY_SERVICE)` | `platform/CarApi.kt` | Car 서비스 연결 | 🟢 |
| `CarPropertyManager.registerCallback(cb, VehiclePropertyIds.GEAR_SELECTION, SENSOR_RATE_ONCHANGE)` | `CarGearSource` | **배송 체크포인트: 기어 P / D** (Pleos SDK 2.3.2가 Gear API를 deprecated 처리하며 GEAR_SELECTION 사용을 안내) | 🟢 등록·초기값(P) · P↔D 변화 수신(Virtual Console) |
| `CarPropertyManager.getIntProperty(GEAR_SELECTION / CURRENT_GEAR)` | `ProbeActivity` | 실측 | 🟢 4(P) |
| `VehiclePropertyIds.PERF_ODOMETER` | (뺌) | 주행거리 대체 경로였음 | ❌ Pleos 이미지에서 `CAR_MILEAGE` 미부여 → 운행 원천에서 제외 |
| `CarUxRestrictionsManager.registerListener` / `isRequiresDistractionOptimization` | `UxRestrictionWatcher` | **주행 중 2줄 화면**, 제한 중에는 움직이는 연출 금지(정적 확인) | 🟢 false(정차) |
| 매니페스트 `distractionOptimized=true` | `DriveActivity` | 주행 중 표시 허용 | ✅ 🔍 |

권한: `android.car.permission.CAR_POWERTRAIN`만. `CAR_MILEAGE`·`CAR_DRIVING_STATE`는 Pleos 이미지에서 서드파티에 부여되지 않아 뺐다(실측 2026-10-30). 주행 제한 상태는 권한 없이 읽혔다

## 4. Android 기본 API

| API | 목적 | 비고 |
|---|---|---|
| `android.speech.tts.TextToSpeech` | (선택) "3건 완료. 다음, ○○" 음성 안내 | **기본 꺼짐**, 계획 화면에서 켬. 시연 핵심 흐름은 화면만으로 완결. Pleos TTS SDK에는 의존하지 않음 |
| `BroadcastReceiver`(`app.nextstop.SIGNAL`) | 시연·테스트용 목 신호 | 디버그 빌드·시연 모드에서만 |
| `NotificationManager`(채널 중요도 HIGH, 소리·진동 없음) · 권한 `POST_NOTIFICATIONS` | 내비가 앱을 덮은 동안 1건 완료를 헤드업 한 줄로 | 앱이 앞에 있으면 쓰지 않음(`CompletionNotifier`) |

## 5. 쓰지 않는 것과 이유

| 항목 | 이유 |
|---|---|
| 외부 경로·지도 API(키 필요) | 공모전 규정 확인 전, 시연장 네트워크·비용 위험. 직선거리×보정계수로 동선 최적화 |
| `onDestinationArrived`를 완료 기준으로 | 에뮬레이터 위치가 안 움직임. 완료는 차량 신호(P·문·D)로 판정 |
| `requestRouteWithWaypoints` / `addWaypoint` | 경유지 수 제한. 1곳씩 교체가 배송 흐름에 맞음 |
| Pleos STT·FusedLocation | 핵심 기능이 준비 중일 수 있는 SDK에 의존하지 않음 |
| 배송 플랫폼 앱 연동·자동화 | 규정·약관 위험. 배송지 입력은 앱 안에서 |

## 6. Pleos 설계 가이드 준수 (원문 대조 2026-10-29)

| 가이드 | 앱 |
|---|---|
| 앱은 플랫폼 CarUxRestrictions에 따라 주행 중 UX를 제한 | UX 제한 또는 기어 P 아님 → 2줄 화면, 버튼 숨김 |
| 주행 중 움직이는 이미지·자동 스크롤로 시선 유도 금지 | 완료 연출은 제한 꺼짐일 때만 움직임. 제한 중엔 정적 확인 1.2초, 연출 도중 제한이 켜지면 즉시 정지 |
| 1~2초 안에 파악할 핵심 정보만 | 주행 화면 정보 2줄(다음 배송지·남은 건수) |
| 쓰는 도메인 권한만 선언 | Pleos 권한 4개(CAR_DOORS, CAR_DRIVE, NAVI_ROUTE, NAVI_ROUTE_SEARCH) |
| 개인·위치 정보 사용 시 약관·처리방침 | 위치 권한 없음, 서버 전송 없음, 실측 로그에 좌표·주소 미기록 |

