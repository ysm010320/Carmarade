# 공모전과 Pleos 조사

2026-11-07 공식 홈페이지 및 개발 문서 확인.

## 대회

현대자동차그룹 차량용 앱 개발 공모전, Code your App into Pleos App Market. 개인 또는 최대 5인 팀으로 참가하며 대학생/일반(스타트업 포함) 부문이 있다. 접수 마감 2026-11-15, 1차 결과 11-23, 최종 PT 12-03~04, 최종 선정 12-18, 전시·시상 2027-02 예정.

평가는 차량특화성·창의성·기술 활용성·사업성이다. 접수양식이 기획서이며 개인정보 동의/서명과 함께 이메일로 접수한다. 시연 영상 또는 소스와 APK를 담은 GitHub 저장소는 선택 가산점이다. APK는 최종 제출 시 실행 가능한 release 버전으로 준비해야 한다. 2차 PT 전에 실제 동작 가능한 앱이 필요하다.

공식 출처:
- https://appdevchallenge.auto-hmg.io/
- https://appdevchallenge.auto-hmg.io/guide/submission
- https://appdevchallenge.auto-hmg.io/guide/judging-awards
- https://appdevchallenge.auto-hmg.io/faq

## 카마라드와 심사 기준

커뮤니티를 중심에 둔 계획서의 의도를 유지한다. 휴대폰 채팅방 대비 차량 가치는 내비 목적지 전달, 주행 맥락에 따른 조작 제한, 차량 위치와 음성 입출력의 결합으로 보여준다. '완전히 같은 도로 경로 복제' 대신 '같은 목적지·경유지 공유와 개별 수락'이라고 설명한다. 현재 프로토타입을 실제 연결된 기능으로 제출 자료에 표현하지 않는다.

## 기술 확인

| 계획 항목 | 공식 문서로 확인한 내용 | 다음 구현 |
|---|---|---|
| 개발 언어 | Java/Kotlin, Android API 28+, Gradle 8+ | Kotlin 네이티브 프로젝트 기반 준비 |
| 목적지·경유지 | NaviHelper 3.0.5, 최대 경유지 2개 | 수락 후 요청, 완료 콜백으로 성공 판정 |
| 같은 경로 | 목적지/경유지 요청은 지원, 차량 간 동일 도로 복제 보장은 확인 못함 | 표기를 공통 목적지·경유지로 제한 |
| 위치 | FusedLocation 1.1.3, isSupported 확인·콜백·퍼미션 필요 | 서버와 지도 제공자 선택 후 연결 |
| 음성 | SpeechToText 3.3.1, 텍스트 반환. 자체 명령 실행은 아님 | 텍스트 의도 처리와 메시지 전달 구현 |
| SDK 가용성 | 신청 가이드에서 Vehicle State/NaviHelper 사용 가능, 위치·음성·차량 제어는 준비 중 표기 | 사용자 프로젝트에서 실제 상태 재확인 |
| 통화·조명 | Telephony 제한 가능, 차량 제어 권한은 승인 범위 | 확장 단계로 유지 |
| 주행 화면 | CarUxRestrictions 기준으로 제한, 카테고리/지역 영향 | 실제 차량 어댑터에서 상태 미확인 시 입력 제한 |
| 네트워크 | 공식 가이드에 끊김/저대역폭 대비 요구 | 재시도·오프라인·오래된 위치 처리 |

문서가 존재한다는 사실만으로 사용자 계정에서 모든 SDK를 사용할 수 있다고 판단하지 않는다. 커뮤니티 화면의 주행 중 사용 허용도 보장하지 않는다. 실제 카테고리를 정하고 플랫폼 심사·제약을 확인한다.

공식 출처:
- https://appdevchallenge.auto-hmg.io/guide/development
- https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
- https://document.pleos.ai/docs/pleos-only/create-my-project
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/waypoint-settings
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro
- https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline
- https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category
- https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/platform-api-architecture

## 다음 차량 연결 작업

1. Pleos 계정의 프로젝트 생성과 SDK 사용 가능 상태 확인. 비밀 키는 소스에 넣지 않는다.
2. API 34 Pleos Emulator 2.1.0에서 현재 APK 설치·실행 확인.
3. `VehicleGateway` 구현에 NaviHelper를 연결. Maven: `ai.pleos.playground:NaviHelper:3.0.5`, 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/`. `initialize(onConnected,onFailed)` 후 리스너 등록, 사용자 수락 후 `requestRouteWithWaypoints`, `onRoutingCompleted` 확인 전 Applied 처리 금지. 실패 시 Unavailable, 종료 시 listener 해제와 release.
4. 위치·음성·차량 제한을 지원하는 어댑터 구현. 기능별 미지원 상태를 화면에 안내한다.
5. 인증된 멤버십을 확인하는 서버 연결. 위치는 해당 모임 멤버만 구독, 공유 해제/퇴장/종료 즉시 서버 삭제. 임의 invite 코드만으로 조회 권한을 주지 않는다.
6. 최신 경로 버전·개별 수락·멤버 오프라인·메시지 중복 방지와 재접속 통합 테스트.
7. 정차/주행 화면, 오디오 포커스, 권한 거부, API 미지원, 네트워크 단절과 지도 제공자 정책 검증.
8. 제출용 release 서명·APK·영상·README·기획서의 구현 완료/예정 표기 일치 확인.

이 문서는 현재 구현되지 않은 기능의 연결 계획이다.
