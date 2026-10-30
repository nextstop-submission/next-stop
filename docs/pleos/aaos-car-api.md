# AAOS Car API (Pleos 공개 SDK가 다루지 않는 부분)

Pleos Connect는 Android Automotive OS 기반이다. 주행 제한과, 공개 여부가 불분명한 기어(Pleos Gear와 이중화)는 **표준 AOSP Car API**로 처리한다.
(출처: AOSP `android.car` 공개 API. 에뮬레이터에서의 동작·권한 허용 여부는 🔍 실측)

## 빌드

```kotlin
android { useLibrary("android.car") }   // compileSdk 플랫폼의 optional/android.car.jar
```

## 기어 — GEAR_SELECTION

```kotlin
val car = Car.createCar(context)
val pm = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
pm.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, /* areaId */ 0)
```

- 값: `VehicleGear.GEAR_PARK(4)`, `GEAR_REVERSE(2)`, `GEAR_NEUTRAL(1)`, `GEAR_DRIVE(8)`, 수동 1~9단(16 이상).
- 권한: `android.car.permission.CAR_POWERTRAIN`.
- `CURRENT_GEAR`도 실측 화면에서 함께 읽는다.

## 주행 거리 대체 — PERF_ODOMETER

- `VehiclePropertyIds.PERF_ODOMETER`(float, km). 권한 `android.car.permission.CAR_MILEAGE`는 서드파티 앱에 허용되지 않는다 ✅ 실측(2026-10-30, `granted=false`).
- 주 경로는 Pleos Odometer.

## 주행 중 화면 제한 — CarUxRestrictions

```kotlin
val ux = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
ux.registerListener { r -> r.isRequiresDistractionOptimization }
```

- 매니페스트 액티비티에 `<meta-data android:name="distractionOptimized" android:value="true"/>`가 있어야 주행 중 표시된다.
- 앱 규칙: UX 제한이 켜졌거나 **기어가 P가 아니면** 주행 화면(다음 배송지 1줄 + 남은 건수 1줄)만 보이고 버튼을 숨긴다.
- 움직이는 완료 연출은 UX 제한이 '꺼짐'으로 확인될 때만 재생한다([driving-restriction.md](driving-restriction.md)).

## 공식 adb 명령 ✅ 원문

```
adb shell cmd hmg_car_service --service drivingstate driving_regulation on|off   # 주행 제한 강제
```

- 기어 변경은 Virtual Console로 하라고 안내한다(adb 명령 가이드 「Change gear state」).
- 클러스터와 같은 속도가 필요하면 `PERF_VEHICLE_SPEED`(CarPropertyManager)를 쓰라고 FAQ가 안내한다 ✅ 원문.

## VHAL 값 주입(비공식, 실측 스크립트가 시도) — Pleos 에뮬레이터에서는 막힘 ✅ 실측

2026-10-30: `SecurityException: The command 'inject-vhal-event' requires non-user build`, `adb root`도 불가(배포용 빌드).

```
adb shell cmd car_service inject-vhal-event 0x11400400 4      # GEAR_SELECTION = PARK
adb shell cmd car_service inject-vhal-event 0x11400400 8      # DRIVE
adb shell cmd car_service inject-vhal-event 0x16400b00 1 1    # DOOR_POS, area ROW_1_LEFT(1) = 1(열림)
```

셸 권한·빌드 유형에 따라 거부될 수 있다. 거부되면 Virtual Console 수동 조작 → 그래도 안 되면 앱 시연 신호(adb 브로드캐스트) 순으로 대체.
