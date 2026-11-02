# NaviHelper SDK

## 의존성 ✅ 샘플 · ✅ 원문

```kotlin
maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
implementation("ai.pleos.playground:NaviHelper:3.0.5")
```

릴리스 노트(2026-10-19)의 공식 버전도 v3.0.5 ✅ 원문.

## 권한 ✅ 원문 · 🔧 SDK

| 권한 | 주요 API | 앱 사용 |
|---|---|---|
| `pleos.car.permission.NAVI_ROUTE` | `get*Info()`, `requestTrip()` | 최근 목적지·즐겨찾기·목적지 확인(직접 추가한 곳의 좌표 받기) |
| `pleos.car.permission.NAVI_ROUTE_SEARCH` | `requestRoute()`, `cancelRoute()`, 경유지·재탐색 | 다음 배송지 전달·안내 종료 |
| `pleos.car.permission.NAVI_CUSTOM_MAP` | `requestNavigationScreen()` | 배송지 직접 추가 — 내비 장소 검색 화면 열기([배송지 직접 추가](../stop-entry.md)) |

SDK aar의 `@RequiresPermission`도 같다: `requestRoute`·`cancelRoute` → `NAVI_ROUTE_SEARCH`, `get*Info` → `NAVI_ROUTE` 🔧.

## 초기화 ✅ 원문

```kotlin
val naviHelper = NaviHelper(applicationContext)
naviHelper.initialize(
    onConnected = { /* 첫 연결 성공: 리스너 등록, API 호출 */ },
    onFailed = { throwable -> /* 연결 재시도 소진 */ },
)
...
naviHelper.removeListener(listener)
naviHelper.release()   // release 후 다시 쓰려면 객체를 새로 만들어 초기화
```

- 문서는 콜백형 `initialize(onConnected, onFailed)`를 권장한다. 앱은 연결 전 요청을 대기열에 두고 `onConnected`에서 처리한다
  (`platform/PleosNavigator.kt`). 연결 전 목적지 요청은 마지막 것 하나만 보관한다.

## 목적지 ✅ 원문

```kotlin
naviHelper.requestRoute(RouteInfo(latitude, longitude, displayLongitude = null, displayLatitude = null,
                                  poiName, poiId, poiSubId, address))
naviHelper.getDestinationInfo()   // 결과는 onDestinationInfo
naviHelper.cancelRoute()
```

- "requestRoute() 성공 ≠ 경로 탐색 완료. 최종 결과는 이벤트 콜백으로." 다시 설정하려면 `requestRoute()`를 다시 호출 → 앱은 배송지 1곳씩 교체 전송.
- 탐색 과정 콜백: `onRouteStarted`, `onRoutingCompleted`, `onDestinationChanged`, `onError(errorCode)`.
- 3.0.5에서 데이터 모델 생성자의 좌표 순서가 (경도, 위도) → (위도, 경도)로 바뀌었다 ✅ 원문(릴리스 노트).
  그런데 같은 문서의 Java 예시는 아직 경도 먼저 쓰여 있어 혼동 여지가 있다 → 앱은 **이름 있는 인자**로 넘긴다.
- `poiId`를 비워도 되는지는 문서에 없다 🔍 → 실측 화면이 `""`, `"0"` 두 변형을 보낸다 → 둘 다 예외 없이 호출됨 ✅ 실측(2026-10-30). 경로 결과 이벤트(`onRoutingCompleted` 등)는 저사양 노트북에서 지도가 그려지지 않아 미확인. 내비에서 가져온 배송지는 원래 POI ID를 쓴다.

## 최근 목적지·즐겨찾기 (배송지 가져오기) 🔧 SDK · ✅ 원문

- `getRecentDestinationInfo()` → `onRecentDestinationInfo(List<RecentDestinationInfo>)`, `getBookmarkInfo()` → `onBookmarkInfo(List<BookmarkInfo>)`.
- 항목에 위도·경도·이름·주소·POI ID·POI sub ID가 있다 🔧 → 외부 지도 API 없이 배송지 목록을 만든다(`core/model/StopImport.kt`).

## 이벤트 ✅ 샘플 · ✅ 원문

배송 흐름에 쓰는 것: `onRoutingCompleted`, `onRouteStarted`, `onRouteCancelled`, `onDestinationArrived`, `onError(NaviErrorCode)`.
`onRouteEnded`는 deprecated 예정 → `onRoutingCompleted` 사용 ✅ 원문(릴리스 노트).

## 내비 실험 (공식 에뮬레이터 v2.1.0, GitHub Actions) ✅ 실측

실험 도구: `ProbeActivity --es lab navi` + `scripts/probe/navi-lab.sh`(CI) · `scripts/probe/navi-lab.ps1`(노트북). 결과 `probe-results/ci-20261031-0228`·`-0249`·`-0322/navi-lab`.

- `requestNavigationScreen(SearchPlace)`·`(Foreground)`는 예외 없이 호출되고 내비가 앞으로 나온다. 다만 CI의 내비는 그래픽 없이 돌아 지도를 그리지 못하고 '내비게이션을 실행 중입니다'(`onNavigationStatus=INITIALIZING`)에 머문다.
- **그래픽을 `guest`로 바꾸면 CI에서도 내비가 동작한다**(5b회차 `ci-20261031-0545`, 워크플로 커밋 메시지 `[gpu-guest]`). 3분 안에 준비를 끝내고, `requestRoute` 뒤 `onRouteStarted(name=실측도착지, distance=1900, duration=480)` → `onRouteEnded`(경로 계산 끝, deprecated 이름) → `onNavigationStatus=ROUTE_DRIVE` → `onDrivingInfo` 순으로 왔다 ✅ 실측. `swangle_indirect`는 '지도 정보를 불러오는 중'에서 내비가 다시 시작되며 멈췄다(4·5a회차).
- `adb emu geo fix`가 만든 NMEA 문장은 내비가 `NMEA checksum mismatch`로 버린다(내비 로그) → 위치를 옮기려면 체크섬을 맞춘 문장을 `adb emu geo nmea`로 보낸다(`scripts/probe/nmea-fix.py`).
- (아래는 기본 `swiftshader_indirect`일 때) `requestRoute` 뒤에도 내비가 INITIALIZING으로 돌아가고 그 뒤 상태·목적지 응답이 없다. NaviHelper를 새로 연결해도(20초 간격 6회) 같다 → CI에서는 `onRouteStarted`·`onDestinationArrived`를 잴 수 없다. 노트북 에뮬레이터(지도·경로·도착 예정 시각이 그려짐)에서 `navi-lab.ps1 -Phase events`로 잰다.
- 목적지가 없을 때 `getDestinationInfo()`는 좌표 0,0·빈 이름을 돌려준다.
- `android.location.Geocoder.isPresent()=false` — 키 없는 기본 지오코더가 없다.
- 내비 화면에서는 `uiautomator dump`가 죽는다(접근성 NPE) → 내비 화면은 캡처로만 본다.

## 앱 설계상 결정

- **도착 판정에 `onDestinationArrived`를 쓰지 않는다.** 에뮬레이터 위치가 움직이지 않고, 배송 완료의 기준은 '정차·하차·출발'이다.
- 내비는 Android GPS 위치를 쓰지 않는다: `geo fix`·NMEA로 Android 위치(`dumpsys location`)는 37.412720 → 37.416320으로 옮겨졌지만 `onCurrentLocationInfo`는 37.412721 그대로였다. Virtual Console 기능 목록(`/vehicle/catalog`)에도 위치·GNSS가 없다 ✅ 실측(CI 8회차 `ci-20261102-1505-navi8`).
- 장소 검색 결과에서 '목적지로'를 누르면 `onDestinationChanged` 없이 곧장 `onRouteStarted`가 온다(7·8회차 두 번 모두). 그래서 앱은 검색 대기 중이면 `onRouteStarted`에서도 `getDestinationInfo`를 묻는다 → 8회차에 배송지로 들어옴 ✅ 실측.
- 로그에는 위치·주소가 담긴 객체를 남기지 않는다(실측 결과가 저장소에 커밋되므로).
- requestRoute가 내비 화면을 앞으로 띄운다(앱 화면을 덮고 목적지 이름을 보여 줌) ✅ 실측(2026-10-30, `probe-results/20261030-auto-host/demo-3-next.png`) → 앱은 완료 확인 화면이 끝난 뒤(1.5초) 목적지를 보낸다.
