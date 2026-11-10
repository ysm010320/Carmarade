# 카마라드 (Carmarade)

**CAR + MARADE, 차로 함께하는 동료.** 각자의 차에서 시작해도, 함께 도착하는 차량용 커뮤니티.

Kotlin Android **1차 체험 버전**입니다. 크루/동행 생성 → 공통 목적지·경유지 수락 → 가상 일행과 주행 → 메시지 → 도착과 후기까지 로컬에서 체험합니다. 사용자가 선택한 단계별 제작 방식에 따라 화면·도메인·차량 연결을 분리했습니다.

## 바로 확인하기

- `apk/carmarade-demo.apk`: Android 9(API 28) 이상용 APK. 테스트용 키로 서명되어 있으며 최종 공모전 제출용 release APK가 아닙니다.
- `preview/index.html`: 브라우저 화면 미리보기. Android 앱과 같은 주요 흐름을 별도로 구현했습니다. 두 실행 환경의 데이터는 서로 연결되지 않습니다.
- `docs/contest-and-sdk.md`: 공모전 조사와 공식 SDK 확인 결과.

미리보기 주소: http://127.0.0.1:4173 (아래 로컬 서버를 실행한 기기에서 접속)

다시 실행하려면 프로젝트 폴더에서:

```sh
python3 -m http.server 4173 --bind 127.0.0.1 --directory preview
```

그다음 브라우저에서 위 주소를 엽니다. 파일을 더블클릭해 여는 경우 브라우저 정책에 따라 저장 기능이 제한될 수 있으므로 로컬 서버를 권장합니다.

## APK 설치와 체험

Android 개발 기기 또는 에뮬레이터에 설치합니다.

```sh
adb install -r apk/carmarade-demo.apk
```

1. `주말엔, 동해` 모임을 엽니다.
2. 목적지와 최대 2개 경유지를 확인합니다.
3. `가상 일행의 경로 수락 시뮬레이션`과 `이 경로 수락하기`를 누릅니다.
4. `동행 주행 체험 시작`을 누릅니다.
5. 빠른 메시지와 위치공유 설정을 체험합니다.
6. `도착 · 동행 체험 종료` 후 후기를 저장합니다.

직접 새 모임을 만들고 가상 멤버를 추가해 같은 흐름을 체험할 수 있습니다. 내 위치공유는 기본 OFF이며 종료 또는 재실행 시 해제됩니다. 저장에 실패하더라도 위치 숨기기와 종료는 현재 실행 상태에 즉시 반영되며 저장 재시도 안내가 표시됩니다.

## 구현 범위

| 기능 | 이번 버전 |
|---|---|
| 크루·동행 생성 | 기기 내 저장, 최대 5명 |
| 초대 | 체험 코드 표시. 원격 가입 없음 |
| 목적지·경유지 | 예시 장소 8곳, 최대 경유지 2개 |
| 경로 수락 | 버전별 개별 수락, 변경 시 재수락 |
| 지도 | 계획 화면은 개념도. 주행 화면은 OpenFreeMap 실제 지도 위에 가상 일행 위치(경로 선 없음, 인터넷 필요). 실제 길 안내는 Pleos 내비 |
| 위치공유 | OFF/대략/정확, 종료·재실행 시 OFF |
| 빠른 메시지 | 지정 멤버 또는 전체에게 보내는 흐름을 로컬 기록 |
| 음성 입력 | Android 기기 음성 인식 제공자가 있으면 사용, 없으면 안내 |
| 읽어주기 | 한국어 TTS 엔진 설치 여부 확인 후 사용자가 켬 |
| 주행 기록·후기 | 로컬 저장. 거리는 시뮬레이션 값 |

실사용 서버, 로그인, 실제 초대·위치·메시지 전달, Pleos NaviHelper/FusedLocation/STT/TTS 연결, 실제 차량 주행 규제, 공개 번개, 전화, 앰비언트 라이트는 아직 연결하지 않았습니다. 이번 앱은 실제 주행에 사용하지 않는 개발 체험 버전이며 `INTERNET`, 위치, 마이크 권한을 선언하지 않습니다. Android 외부 음성 인식기를 사용하면 그 제공자의 네트워크·개인정보 정책이 적용될 수 있습니다.

## 구조와 수정 위치

- `core/Trip.kt`: 모임·경로·수락·메시지·개인정보 규칙과 저장 실패 처리.
- `app/src/main/java/ai/camarade/app/MainActivity.kt`: 화면과 사용자 흐름.
- `app/src/main/java/ai/camarade/app/Ui.kt`: 색상, 글꼴, 버튼, 카드.
- `app/src/main/java/ai/camarade/app/RouteMapView.kt`: 차량용 동행 개념도.
- `app/src/main/java/ai/camarade/app/TripStore.kt`: 로컬 저장·복원.
- `app/src/main/java/ai/camarade/app/vehicle/VehicleGateway.kt`: 차량 연동 교체 지점.
- `preview/`: 설치 없이 디자인을 검토하는 브라우저 미리보기.

Android Studio에서 이 폴더를 열고 Android SDK 34 및 JDK 17을 설정합니다. Gradle 8.14, Android Gradle Plugin 8.6.1, Kotlin 2.0.21을 사용합니다.

```sh
./gradlew :app:assembleDebug
```

일반 Gradle 빌드는 이 환경에서 실행하지 못했습니다. 이 기기에서는 디스크 공간 제약 때문에 기존 Kotlin 컴파일러와 공식 Android aapt2/D8/apksigner를 사용한 `scripts/build-demo.sh`로 APK를 생성했습니다. 스크립트의 로컬 기본 경로는 환경변수 `CAMARADE_JDK`, `CAMARADE_GRADLE_LIB`, `CAMARADE_ANDROID_TOOLS`로 바꿀 수 있습니다. `.toolchain`은 이 기기의 빌드 캐시이며 소스 배포에 포함하지 않습니다.

## 검증 결과 (2026-11-07)

- `bash scripts/test-core.sh`: 14개 동작 테스트 통과.
- Android 리소스·Kotlin 컴파일·D8 변환·APK 서명 검증 통과.
- API 28/target 34 및 실행 Activity를 APK에서 확인.
- 브라우저에서 모임 생성, 빈 이름 거부, 경로 미수락 출발 차단, 경유지 2개 제한과 재수락, 메시지, 위치공유 변경/재실행 해제, 종료 및 후기 저장/복원 확인.
- 독립 코드 검토 후 저장 실패 시 개인정보 해제와 잘못된 성공 알림 문제를 수정.
- Android/Pleos 에뮬레이터 및 실차 구동, Android 음성 입출력, 전체 Gradle/lint는 미검증. APK 생성과 실제 기기 구동 검증은 구분합니다.

공모전 제출과 실제 서비스 배포는 아직 수행하지 않았습니다.
