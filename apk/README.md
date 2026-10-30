# 제출용 APK

- `NextStop_다음집_release.apk` — `./gradlew :app:assembleRelease` 결과(`app/build/outputs/apk/release/app-release.apk`)를 이 이름으로 복사한다.
- 생성: `./gradlew :app:assembleRelease`(release 빌드). 해시는 `.sha256` 파일.
- 서명: 빌드 환경의 디버그 키(심사 설치용, 스토어 배포 아님). 다른 빌드가 이미 설치돼 있으면 서명이 달라 `adb uninstall app.nextstop` 후 설치.
- 설치: `adb install -r apk/NextStop_다음집_release.apk`
- 대상: Pleos Connect Emulator(Android 14). 일반 폰 이미지에는 Pleos Vehicle Service가 없어 차량 신호가 오지 않는다(시연 신호 모드는 동작).
- 첫 실행 때 알림 권한을 묻는다: 내비가 화면을 덮은 동안 1건 완료를 한 줄로 알리고, 운행 중 '다음집 운행 중' 고정 알림을 띄운다(포그라운드 서비스). `adb install -r -g`로 설치하면 권한이 미리 허용된다.
