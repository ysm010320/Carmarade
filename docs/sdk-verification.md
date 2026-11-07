# Pleos SDK 사용 가능 여부 직접 확인

2026-11-07. 클라우드 개발 환경에서 Pleos Maven 저장소의 SDK를 직접 내려받아 확인했습니다. 실제 API 소스·바이트코드와 권한을 읽었고, 이 프로젝트에 SDK를 붙여 Gradle 빌드까지 해 봤습니다. 공식 문서도 다시 읽었습니다.
이 환경에는 KVM이 없어 **Pleos 에뮬레이터 실행은 하지 못했습니다.** 실행 확인은 Android Studio에서 해야 합니다(아래 "에뮬레이터에서 남은 확인" 참고).

## 결론

| SDK (최신 버전) | 받을 수 있나 | 우리 앱이 호출 가능? | 공식 사용 상태 | 카마라드 용도 |
|---|---|---|---|---|
| NaviHelper 3.0.5 | 예(계정 불필요) | 예. 권한 보호 수준 normal | **사용 가능(신청 불필요)** | 목적지·경유지 전송, 도착 이벤트 |
| Vehicle 2.3.2 (상태 조회) | 예 | 예. `CAR_*` 권한 normal | **사용 가능(신청 불필요)** | 기어(P단) 등 차량 상태 |
| Vehicle 2.3.2 (제어) | 예 | 매니페스트상 `CONTROL_CAR_*` 대부분 normal | 준비 중(Coming Soon) | 웰컴 라이트(앰비언트 색상) |
| FusedLocation 1.1.3 | 예 | 예. `FUSED_LOCATION` | 준비 중(Coming Soon) | 내 차 위치 |
| SpeechToText 3.3.1 | 예 | 기본 기능만. 클라이언트 ID·시크릿 필요 | 준비 중(Coming Soon) | 음성 메시지 입력 |
| TextToSpeech 3.3.1 | 예 | 예. 클라이언트 ID·시크릿 필요 | 준비 중(Coming Soon) | 받은 메시지 읽어주기 |
| LLM 2.1.3.2 | 예 | 확인 안 함 | 문서상 별도 | (사용 계획 없음) |

공식 문서 원문: "FusedLocation, TextToSpeech, SpeechToText, Vehicle Control SDK가 이 상태(준비 중)에 해당", "Vehicle State와 NaviHelper SDK는 신청 없이 바로 사용 가능"
(https://document.pleos.ai/docs/pleos-only/create-my-project)

**의미:** 지금 확실하게 쓸 수 있는 차량 기능은 **내비 경로 전송(NaviHelper)과 차량 상태 조회** 두 가지입니다. 위치·음성·조명은 라이브러리가 공개되어 있어 코드는 짤 수 있지만, 우리 프로젝트에서 실제로 쓰려면 Pleos가 "신청 필요"로 열어 주고 승인해야 합니다. 기획서에는 이 세 가지를 "Pleos 제공 예정 SDK 연동"으로 적고, 안 열릴 경우의 대안도 함께 적는 것이 안전합니다.

## SDK별 확인 내용

### NaviHelper 3.0.5 (사용 가능)
- 권한: `pleos.car.permission.NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP` 모두 protection level **normal**이라 일반 앱이 매니페스트 선언만으로 사용합니다. 시스템 앱 전용은 내부 API 1개(`updateOccupiedRegion`)뿐입니다.
- 경유지: `WaypointIndex`가 `FIRST`, `SECOND`, `ALL`뿐이라 **경유지 최대 2개**가 코드로 확인됩니다.
- 쓸 수 있는 이벤트: `onRoutingCompleted`(경로 적용 완료), `onDestinationArrived`, `onWayPointArrived`, `onCurrentLocationInfo`(현재 위도·경도·방향), `onTripInfo`(거리·ETA), `onCurrentRoadSpeedLimit`, `onError`.
  - **도착 시 위치공유 자동 종료**는 `onDestinationArrived`로 구현할 수 있습니다.
  - `onCurrentLocationInfo`가 위치를 주므로, FusedLocation이 열리지 않아도 **내비 경로 안내 중에는 내 위치를 얻을 수 있을 가능성**이 있습니다(에뮬레이터 확인 필요).
- 지도: `requestNavigationScreen`은 내비 화면을 여는 기능(경로 개요, 장소 검색, POI 상세 등)뿐입니다. **내비 지도 위에 다른 차량 아이콘을 그리는 API는 없습니다.** 일행 위치는 앱 자체 화면에 그려야 합니다.
- 장소 검색 API는 없습니다. `NavigationScreenRequest.SearchPlace(keyword)`로 내비의 검색 화면을 열 수는 있습니다.

### Vehicle 2.3.2
- 조회 권한 `CAR_*`(DRIVE, LIGHTS, ENERGY 등)는 모두 normal입니다. `PRIVATE_CAR_SAFETY`와 창문 제어 1개는 signature|privileged라 우리 앱은 쓸 수 없습니다.
- 인수인계 문서에 없던 **Gear API**(`getCurrentGearSelectionState`, `registerGearSelection`)가 있습니다. P단 여부로 정차를 판단해 입력 제한을 보조할 수 있습니다. 다만 공식 가이드는 주행 제한 판단에 Android `CarUxRestrictions`를 쓰라고 합니다.
- 앰비언트 라이트: `setAmbientLightBrightnessColor(SeatArea, ARGB)` 등 존재. 매니페스트 권한 수준은 normal이지만 "Vehicle Control"이 준비 중이라 현재는 사용할 수 없습니다.
- 차속 API는 이번에도 찾지 못했습니다(크루즈 목표 속도만 있음).

### FusedLocation 1.1.3 (준비 중)
- `isSupported()`는 차량에 위치 서비스 앱이 설치되어 있는지만 봅니다. 권한은 `FUSED_LOCATION`입니다.
- `LocationData`: 위도·경도·고도·방향·신뢰도·시각. 속도 필드는 없습니다(인수인계 내용과 같음).
- `addProximityAlert`는 고정 좌표 지오펜스입니다. 차량 간 이탈 판단은 서버에서 해야 합니다.

### SpeechToText / TextToSpeech 3.3.1 (준비 중)
- 모드: `ON_DEVICE`, `SERVER`, `HYBRID`. 서버 모드에는 Pleos 프로젝트의 클라이언트 ID·시크릿으로 `registerApp`이 필요합니다. 이 키는 소스에 넣지 않아야 합니다.
- 권한: `pleos.car.permission.STT_SERVICE`, `TTS_SERVICE`.
- STT의 `CallApi`(통화 음성 송수신 인식)와 `AmbientAi`(호출어 등)는 **Pleos 자체 앱 패키지만 허용하는 특권 API**라 우리 앱은 쓸 수 없습니다. **전화 걸기 API는 여전히 없습니다.**
- TTS는 `setAudioZoneId`, 화자(`setSpeakerType`)를 지원합니다.

## 빌드로 확인한 것

- **현재 앱 Gradle 빌드 성공**(`./gradlew :app:assembleDebug`). 팀 README에 "미검증"으로 남아 있던 항목입니다. Android SDK 34, build-tools 34.0.0, Gradle 8.14.3, AGP 8.6.1.
- **lint: 오류 0개, 경고 9개**(onDraw 안의 객체 생성 5건 등, 동작에는 지장 없음).
- **SDK 연결 컴파일 성공:** 프로젝트 복사본에 NaviHelper, FusedLocation, Vehicle, STT, TTS를 붙이고, 경로+경유지 요청·도착 이벤트·위치 콜백·기어 리스너·앰비언트 색상·STT·TTS를 호출하는 검사용 코드를 넣어 APK까지 만들었습니다. 저장소에는 넣지 않았습니다.
  - SDK를 붙일 때 필요한 변경:
    - `settings.gradle.kts`에 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/` 추가
    - `gradle.properties`에 `android.useAndroidX=true` 추가(STT/TTS가 AndroidX를 끌어옴)
  - **APK 크기가 2.5MB에서 약 30MB로 늘었습니다.** STT/TTS가 끌어오는 보안 모듈(universal-access)이 Material·Dagger 등을 포함하기 때문입니다. NaviHelper·Vehicle만 쓰면 훨씬 작을 것으로 예상합니다(미측정).
  - 병합된 매니페스트에 `INTERNET` 권한이 SDK를 통해 자동으로 들어옵니다.

## 공식 가이드에서 새로 확인한 제약

- **앱 카테고리:** 주행 중 기능을 제한적으로 허용하는 예시가 있는 카테고리는 Music/Audio, Maps/Navigation, **Mobility Services**뿐입니다. Social/Communication은 예시에 없습니다. Mobility Services 예시에 "현재 위치 기반의 즉각적인 정보 제공", "경유지 설정"이 있으므로 **카마라드는 Mobility Services로 등록하는 편이 주행 중 화면을 쓰기에 유리해 보입니다.** 프로젝트 생성 시 카테고리를 정해야 하므로 먼저 결정해야 합니다.
  (https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category)
- **주행 제한:** `CarUxRestrictions`로 제한 상태를 확인해 UI를 줄이거나 끄고, 키보드보다 프리셋·음성을 우선해야 합니다. 현재 프로토타입의 "주행 중 키보드 금지, 빠른 메시지·음성 우선" 방향과 일치합니다.
  (https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline)
- **최소 SDK API 28:** 차량 보안 요건(서명 키 교체) 때문이라고 명시되어 있습니다. 현재 설정과 일치합니다.

## 에뮬레이터에서 남은 확인 (Android Studio)

1. SDK Manager → SDK Update Sites에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`(Windows/Linux) 또는 `.../crp-arm64-sys-img.xml`(Mac) 추가.
2. API 34의 "Pleos Connect v2.1.0" 이미지 설치(x86_64 이미지 약 4GB). Automotive 기기로 AVD 생성, Cold boot, RAM 4GB.
3. 현재 APK 설치 후 화면·조작 확인.
4. SDK 검사용 빌드를 설치해 확인:
   - NaviHelper 경로 요청에 `onRoutingCompleted`가 오는지
   - `onCurrentLocationInfo`로 위치가 오는지
   - FusedLocation·STT·TTS가 에뮬레이터에서 실제로 응답하는지, 아니면 권한·승인 오류가 나는지
5. 주행 상태를 바꿔 `CarUxRestrictions` 변화와 앱 동작 확인(Virtual Console).
