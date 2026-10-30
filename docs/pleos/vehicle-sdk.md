# Vehicle SDK

## 의존성

```kotlin
// settings.gradle.kts
maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
// app/build.gradle.kts
implementation("ai.pleos.playground:Vehicle:2.3.2")
```

- 토큰·credentials 불필요 ✅ 샘플.
- 버전: SDK 릴리스 노트(2026-10-19)는 **Vehicle SDK v2.3.1**을 공식 버전으로 적고 있으나 ✅ 원문, Maven에는 **2.0.3·2.3.2만** 있다 🔧.
  공식 샘플 README도 2.3.2를 쓴다 ✅ 샘플 → 이 저장소는 2.3.2.
- 2.3.1 주요 변경 ✅ 원문: `DoorArea` 등 영역 객체가 값 기반 `equals/hashCode`로 바뀜(같은 영역 값이면 같은 키). `VehicleSpeedListener` 제거.

## 세션 수명주기 ✅ 원문·샘플

```kotlin
val vehicle = Vehicle(context.applicationContext)
vehicle.initialize()          // 이후 도메인 사용
val door = vehicle.getDoor()  // 도메인 객체는 getXxx()로만 얻는다
...
vehicle.release()             // 리스너는 release 전에 해제
```

- capability/get/set 결과는 비동기 `onComplete`/`onFailed`로 온다.
- 구독: `registerXxx(listener)` → 값이 바뀌면 `onXxxUpdated`, 실패는 `onFailed(Exception)`. 해제는 같은 리스너 인스턴스로 ✅ 원문.

## 권한 ✅ 원문 · 🔧 SDK

문서: "Declare only permissions for domains you use", 각 API의 `@RequiresPermission`을 확인할 것.

| 앱이 쓰는 API | 권한 (SDK 2.3.2 `@RequiresPermission`) |
|---|---|
| `Door.getCurrentDoorPosition`, `registerDoorPosition` | `pleos.car.permission.CAR_DOORS` |
| `Odometer.getCurrentDistanceDriven`, `registerDistanceDriven` | `pleos.car.permission.CAR_DRIVE` |

- 문서 권한 표: DrivingMode·Brake·**Odometer → `CAR_DRIVE`**. 공식 샘플 매니페스트의 `CAR_MILEAGE`는 SDK 바이너리의 권한 상수 목록에 없다 🔧.
- 권한은 protectionLevel normal(런타임 요청 없음) ✅ 샘플. 누락 시 `onFailed`로 예외(`ERROR_SECURITY_PERMISSION`) ✅ 원문.

## 오류 코드 ✅ 원문 · 🔧 SDK

| ErrorCode | 의미 | 앱 처리 |
|---|---|---|
| `ERROR_NOT_SUPPORTED_PROPERTY` / `_AREA` | 미지원 속성·영역 | 해당 신호 원천 비활성, 시연 신호로 대체 가능 |
| `ERROR_SECURITY_PERMISSION` | 권한 부족 | 매니페스트 점검 안내 |
| `ERROR_SERVICE_CONNECTION_FAILURE` / `ERROR_INTERNAL_SERVICE` | 서비스 연결·내부 오류 | 1.5초 간격 재시도(최대 4회) |
| `ERROR_DRIVING_RESTRICTED` | 주행 중 제한 | 정차 후 재시도 |

- 문서는 `VehicleException.errorCode`로 읽으라고 하지만, 2.3.2 aar에서는 그 클래스가 난독화돼 있다(`callback.exception.a`) 🔧.
  `ErrorCode`(vehicle-data 1.1.11의 `ai.pleos.playground.vehicle.constant.ErrorCode`)를 반환하는 무인자 메서드를 찾아 읽는다 → `platform/VehicleErrors.kt`.

## Door (배송 체크포인트의 '문') ✅ 원문·샘플

| 용도 | 호출 |
|---|---|
| 지원 여부·범위 | `door.checkDoorPositionCapabilities(onComplete = { config, areaBounds -> }, onFailed = { })` |
| 현재값 | `door.getCurrentDoorPosition(area = DoorArea(DoorValue.ROW_1_LEFT), onComplete = { pos: Int? -> }, onFailed = { })` |
| 구독 | `door.registerDoorPosition(area, listener: DoorPositionListener)` / `unregisterDoorPosition(area, listener)` |

- `DoorPositionListener { onPositionUpdated(pos: Int?); onFailed(e: Exception) }` ✅ 원문.
- 샘플 AVD의 Door Position 범위 `0..1`(닫힘/열림) → 앱은 `pos > 0`을 열림으로 본다. Virtual Console 앞 왼쪽 Open → `ROW_1_LEFT=1` ✅ 실측(2026-10-30). Door capability는 `READ_WRITE`, 영역 6개(범위 0..1 넷, 0..100 둘), 3열 문(`ROW_3_*`)은 미지원.
- 앱이 문을 여는 게 아니라 Virtual Console에서 바뀐 값을 구독한다(샘플 기록: SET은 완료돼도 값은 그대로) ✅ 샘플.

## Odometer (실주행거리) 🔧 SDK · ✅ 원문

| 용도 | 메서드 |
|---|---|
| **누적 주행거리** | `getCurrentDistanceDriven(onComplete: (Float?) -> Unit, onFailed)`, `checkCurrentDistanceDrivenCapabilities` |
| 누적 주행거리 구독 | `registerDistanceDriven(DistanceDrivenListener)` / `unregisterDistanceDriven` — `onDistanceDrivenUpdated(@Nullable Float)` |
| 트립 거리·시간, 주행가능거리 | `getTripCurrentDrivingDistance`, `getTripCumulativeDrivingTime`, `getRangeRemainingDistance` 등 |

- Virtual Console **Motion > Odometer** 카드로 누적 거리를 0~1,000,000km, 1km 단위로 바꿀 수 있다 ✅ 원문 → 시연에서 계기판 실측 정산을 보여 줄 수 있다.
- 값 단위는 km ✅ 실측(2026-10-30: Virtual Console `traveled_distance_km=3` → `onDistanceDrivenUpdated` 3.0). 트립 거리·시간 API는 에뮬레이터에서 `ERROR_NOT_SUPPORTED_PROPERTY`.

## 기어 🔧 SDK

- SDK에 `Vehicle.getGear()`가 있으나 **2.3.2에서 deprecated**, 컴파일러 경고: "This API is deprecated, use the Google GEAR_SELECTION property." 🔧
- 앱은 AAOS `GEAR_SELECTION`만 쓴다([aaos-car-api.md](aaos-car-api.md)).

## Vehicle Service 패키지 ✅ 샘플

`ai.pleos.playground.service.vehicle` — 일반 폰 이미지에는 없다. Pleos 에뮬레이터/차량에서만 동작.
