# 주행 제한과 차량용 화면 설계

출처: 「Driving Restriction Development Guide」, 「Driving Restriction Policy by Region」, 「Designing apps for driving」, 「Typography」 ✅ 원문 (2026-10-29)

## 플랫폼 규칙 ✅ 원문

- Pleos Connect 앱은 플랫폼이 주는 **CarUxRestrictions** 상태에 따라 주행 중 UX 제한을 적용해야 한다.
  `CarUxRestrictionsManager.registerListener()` → `onUxRestrictionsChanged()`로 갱신을 받는다.
- Distraction Optimization(DO)을 선언한 앱은 제한이 켜졌을 때 `isRequiresDistractionOptimization()`과 `getActiveRestrictions()`를 보고 기능별 제한을 **스스로** 적용한다.
- 지역 식별: `product.sw.target_region` (한국 `KOR`, 유럽 `EUR`, 북미 `USA`/`CAN`).

## 주행 제한이 켜지고 풀리는 기준 ✅ 원문

| 지역 | 해제 조건 |
|---|---|
| 북미 이외(한국 포함) | 시동(IGN1)·주행 준비 꺼짐, 또는 **속도 5km/h 이하이면서 P / 주차 브레이크 ON / 오토홀드 유지 중 하나** |
| 북미 | 시동 꺼짐, 또는 5km/h 이하이면서 자동변속기 P(수동은 주차 브레이크 + N). 오토홀드는 정차로 인정 안 함 |

→ 앱에 주는 의미: **D를 넣는 순간(주차 브레이크·오토홀드가 아니면) 제한이 켜질 수 있다.** '문 닫고 D → 다음 집' 전환은 제한 상태에서도 규칙을 지켜야 한다.

## 설계 원칙 ✅ 원문

- 주행 중 시각·인지 부담 최소화. **동영상, 움직이는 이미지, 자동 스크롤 텍스트로 운전자 시선을 끌지 말 것.**
- 한 손, 간단한 조작만. 손발을 옮기게 하는 동작 유도 금지.
- 한눈에(1~2초) 파악할 핵심 정보만. 입력 후 응답 0.25초 이내(지연 시 진행 표시).
- 글자: 최소 12sp, 줄 높이 1.5배 이상 권장.

## 앱 적용

| 상황 | 화면 |
|---|---|
| 제한 꺼짐(정차) | 다음·그다음 배송지, 힌트, 완료·나중에·건너뛰기 버튼 |
| 제한 켜짐 또는 기어 P 아님 | 다음 배송지 1줄(104sp) + 남은 건수 1줄(52sp). 버튼 없음 |
| D로 1건 완료 — 제한 꺼짐 | 플래시·카운트 상승·카드 슬라이드 연출(약 1.4초) |
| D로 1건 완료 — 제한 켜짐 또는 상태 모름 | **움직임 없는 확인 화면**(✓, `n / 전체`, 완료한 배송지) 1.2초 후 다음 배송지 |
| 연출 도중 제한이 켜짐 | 즉시 움직임을 멈추고 확인 화면으로 전환 |

## 시험 방법 ✅ 원문

```
adb shell cmd hmg_car_service --service drivingstate driving_regulation on    # 주행 제한 강제 ON
adb shell cmd hmg_car_service --service drivingstate driving_regulation off   # 해제
```

모든 차종 공통 명령(문서). 실측 스크립트가 ON/OFF 때 앱의 UX 제한 콜백이 바뀌는지 기록한다.
