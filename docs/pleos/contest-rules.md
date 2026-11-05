# 공모전 규정 중 개발에 영향 주는 것

출처: 공모전 공식 사이트·FAQ(직접 확인한 내용), 공개 보도. 최종 기준은 공식 공지.

| 항목 | 내용 | 앱 반영 |
|---|---|---|
| 개발 환경 | Android API 28+, Gradle 8.0+, Kotlin | minSdk 33(NaviHelper 샘플 기준), Gradle 8.10.2, Kotlin 2.0 |
| 사용 가능 SDK | Vehicle, NaviHelper, TTS, STT, FusedLocation (상태는 각 SDK 문서 기준) | 핵심은 Vehicle(Door·Odometer)+NaviHelper. 음성 안내는 선택 기능(기본 꺼짐, Android 기본 TTS) — 없어도 흐름 완결 |
| SDK 공개 버전(릴리스 노트 2026-10-19) | Vehicle v2.3.1, NaviHelper v3.0.5, TextToSpeech v3.3.1, SpeechToText v3.3.1, FusedLocation v1.1.2 | Maven 실제 배포는 Vehicle 2.3.2 → 2.3.2 사용. FusedLocation의 Proximity Alert(지오펜스)는 도착 임박 안내 확장 후보(에뮬레이터 위치 고정이라 시연 핵심 제외) |
| 앱 심사 가이드(참고) | 운전자 주의 분산 요소 최소화, 개인·위치 정보 사용 시 약관·처리방침 | 위치 권한 없음, 서버 없음, 실측 로그에 좌표 미기록 |
| 제출 | GitHub 저장소에 소스+APK. 비공개면 심사용 계정 Collaborator 초대 | `apk/`에 release APK, README |
| 심사 | 차량특화성·창의성·기술 활용성·사업성 + 시연영상·GitHub 가산점 | README·docs/sdk-usage.md로 기술 활용 근거 |
| 일정 | 접수 마감 11/15 23:59, 1차 발표 11/23, 12/3~4 오프라인 PT(에뮬레이터 실시연) | 시연 대본 `docs/demo/` |
| 익명 심사 | 실명·학교·연락처·계정명 노출 금지 | 커밋 작성자 익명, 문서·화면에 개인정보 없음 |

> 주의: 비공개 저장소의 **소유자 계정명은 저장소 URL에 그대로 드러난다.** 익명성이 엄격하면 제출용 익명 계정/조직으로 저장소를 옮기는 것을 검토(사람 작업).
