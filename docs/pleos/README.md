# Pleos 문서 정리

이 폴더는 앱이 쓰는 Pleos Connect SDK·에뮬레이터 정보를 **출처와 함께** 모아 둔 것이다.

## 출처와 확인 수준

| 기호 | 뜻 |
|---|---|
| ✅ 원문 | document.pleos.ai 공식 문서 원문으로 확인 (2026-10-29 대조) |
| ✅ 샘플 | Pleos 공식 GitHub 샘플 코드에서 직접 확인(컴파일되는 코드 기준) |
| 🔧 SDK | 공식 Maven의 SDK aar(클래스·`@RequiresPermission`·nullability)에서 확인 |
| 🔍 실측 | 에뮬레이터 실측으로 확인해야 함(`scripts/probe/run-probe.ps1`, 결과는 `docs/probe-results/`) |

공식 샘플 저장소(참고 허용):
- `Pleos-Playground/pleos-sample-vehicle-app` — Vehicle SDK 2.3.2, Door 타입 API 레퍼런스
- `Pleos-Playground/pleos-sample-navi-app` — NaviHelper SDK 3.0.5 전체 API 호출 예

## 원문 대조에서 바뀐 것 (2026-10-29)

| 항목 | 이전 판단 | 원문·SDK 근거 | 조치 |
|---|---|---|---|
| Odometer 권한 | 샘플을 따라 `CAR_MILEAGE` | 권한 표: Odometer → `CAR_DRIVE` ✅, SDK `@RequiresPermission(CAR_DRIVE)` 🔧 | 매니페스트에 `CAR_DRIVE` 추가, 쓰지 않는 도메인 권한 제거 |
| 완료 연출 | 항상 애니메이션 | 설계 원칙: "주행 중 동영상·움직이는 이미지로 시선을 끌지 말 것" ✅ | 플랫폼 UX 제한이 꺼져 있을 때만 움직임, 그 외 정적 확인 |
| 주행 제한 판정 | 기어 기준 추정 | 한국 등: 5km/h 이하 + (P·주차브레이크·오토홀드) 중 하나면 해제 ✅ | D를 넣는 순간 제한이 켜질 수 있음을 전제로 설계 |
| NaviHelper 초기화 | `initialize()` | `initialize(onConnected, onFailed)` 권장 ✅ | 연결 콜백 + 요청 대기열 |
| Virtual Console | 사용법 미확인 | 브라우저 `127.0.0.1:8088`, 기어·도어·Odometer·착석·비상등 카드 ✅ | 실측·시연 순서서 갱신 |
| 주행 제한 강제 토글 | 없음 | `adb shell cmd hmg_car_service --service drivingstate driving_regulation on/off` ✅ | 실측·시연 스크립트에 추가 |
| Vehicle SDK 버전 | 2.3.2 (실패 시 2.3.1) | 릴리스 노트는 v2.3.1, Maven에는 2.0.3·2.3.2만 존재 🔧 | 2.3.2 고정, 2.3.1 대체 경로 삭제 |
| VehicleException | 문서대로 타입 검사 | SDK 2.3.2에서 난독화된 클래스 🔧 | `ErrorCode` 반환 메서드로 코드 추출(`VehicleErrors`) |

## 파일

- [vehicle-sdk.md](vehicle-sdk.md) — Vehicle 세션·Door·Odometer·권한·오류 코드
- [navihelper-sdk.md](navihelper-sdk.md) — 초기화·목적지 전달·최근 목적지·이벤트·권한
- [driving-restriction.md](driving-restriction.md) — CarUxRestrictions, 지역별 주행 제한 기준, 설계 원칙
- [virtual-console.md](virtual-console.md) — 에뮬레이터 차량 신호 조작(시연 경로)
- [aaos-car-api.md](aaos-car-api.md) — 기어(GEAR_SELECTION)·UX 제한·대체 주행거리
- [emulator.md](emulator.md) — Pleos Connect Emulator 설치·실행 요구 사항
