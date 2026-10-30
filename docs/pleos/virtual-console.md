# Pleos Connect Virtual Console (시연의 차량 신호)

출처: 「Pleos Connect Virtual Console Setup Guide」, 「User Guide」 ✅ 원문 (2026-10-29)

## 여는 법

- Pleos Connect Emulator **v2.1.0 이상** 필요.
- 에뮬레이터 실행 후:
  ```
  adb forward tcp:8088 tcp:8088
  ```
  PC 브라우저에서 `http://127.0.0.1:8088/`
- 안 열리면: 에뮬레이터 실행 여부 → 이미지 버전 → `adb devices` → 포트 포워딩 재실행 → 8088 포트 점유 프로세스 → 브라우저 재접속.

## 앱에 쓰는 카드

| 도메인 | 카드 | 범위 | 앱에서 |
|---|---|---|---|
| Motion | **Gear Selection** | P / R / N / D | 체크포인트(정차·출발) |
| Convenience | **Door Position** | 앞·뒤 좌·우 문, 열림/닫힘 | 체크포인트(하차·승차). 앱은 운전석(ROW_1_LEFT) 사용 |
| Motion | **Odometer** | 0~1,000,000km, 1km 단위 | 계기판 실측 정산 — 배송지마다 1~2km 올리면 정산이 '계기판 실측'으로 표시 |
| Convenience | Seat Occupancy | 운전석 등 Empty/Occupied | (확장 후보) 하차 확인 신호 |
| Convenience | Hazard Lights | Off/On | (확장 후보) 비상등 켜고 정차 = 배송 정차 신호 |
| Safety | Telltales | 주차 브레이크 등 Off/On/Blink | 주행 제한 해제 조건과의 관계 🔍 |

- 사용 순서: Emulation Controls → 왼쪽 도메인 선택(또는 카드 이름 검색) → 영역 선택 → 버튼·목록·슬라이더로 값 변경 → 에뮬레이터·앱에서 결과 확인.
- 기어가 P일 때 상단 Theme 버튼으로 Highway/Forest 테마를 바꿀 수 있다(시험 기능과 무관).

## 실제 화면 구성 (CI 실측 2026-10-30, `docs/probe-results/ci-20261030-0119/vc/`) 🔍 실측

공식 가이드의 'Emulation Controls' 목록 말고, 첫 화면(3D 주행 화면 + 아래 카드)에서 바로 조작할 수 있다.

| 조작 | 화면 위치 | 보내는 요청(관찰) |
|---|---|---|
| 기어 P/R/N/D | 오른쪽 아래 속도계 옆 `P R N D` 버튼 | `PUT /vehicle/gear?gear=Park` · `…=Drive` |
| 운전석 문 | BODY 카드 차 그림의 `FL door`(누를 때마다 열림↔닫힘) | `PUT /vehicle/body?door_fl=1`(열림) |
| 가속·감속 | 왼쪽 `ACCEL`·`BRAKE` 패드(누르고 있는 동안) | `PUT /vehicle/controls?speed=…&accelerator=…&brake=…` |
| 주행거리(Odometer) | Emulation Controls > Motion > Odometer | `PUT /vehicle/odometer?traveled_distance_km=3`(노트북 실측 10/30, 앱이 3.0km 수신) |
| 상태 확인 | 오른쪽 아래 `State` | `GET /vehicle/state` — `gear.bridge_ready`, `gear.actual`, `body.doors.front_left` 등 |

- `gear.bridge_ready`가 false인 동안(차량 HAL과 연결 전)에는 눌러도 차에 반영되지 않는다(노트북 실측: `emulator_active=false`면 제어 요청이 HTTP 503). 부팅 직후 바로 누르지 말고 상태가 true가 된 뒤 조작한다.
- 화면 안내문: 기어가 P일 때 "Select D + hold ACCEL", D일 때 "ACCEL to speed up · BRAKE to slow". 시연에서는 속도를 올리지 않는다(정차 상태로 P·문·D만 바꾼다).
- CI는 이 화면을 `scripts/probe/vc-drive.mjs`로 헤드리스 브라우저에서 누른다(사람이 하는 조작과 같다). Virtual Console 파일 자체는 저장하지 않는다.

## 시연 순서 (1건)

1. Motion > Gear Selection **D** (운행 시작 후 출발)
2. Motion > Odometer **+2km**
3. Motion > Gear Selection **P**
4. Convenience > Door Position (앞 왼쪽) **Open** → **Close**
5. Motion > Gear Selection **D** → 앱: 1건 완료 확인 → 다음 배송지
