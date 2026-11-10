# Taxi-Talk

택시 기사(한국어) ↔ 외국인 승객(EN/JA/ZH) **좌석 분리형 실시간 통역** Pleos 앱.
요구사항: [docs/requirements.md](docs/requirements.md)

## 빌드 / 실행
- Android Studio 내장 JBR, Gradle 9.3.1 wrapper, AGP 8.13.2, Kotlin 2.3.20, compileSdk/targetSdk 34, minSdk 28
- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Pleos Connect 에뮬레이터(v2.1.0, API 34): `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- QR 진입 모사: `adb shell am start -a android.intent.action.VIEW -d "taxitalk://ride?lang=ja"`

## 화면 구성 (와이드 디스플레이)
| 왼쪽: 운전석 | 오른쪽: 뒷좌석(승객 화면 모사) |
|---|---|
| 한국어, 큰 글씨, 승객 발화의 한국어 통역 + 원문 | 승객 언어 UI, 언어 선택/자동 인식, 동의, 대화 말풍선(원문+번역), 차량 안내 배너 |
하단 **시연 패널**: 착석/벨트/기어/문/심야/ETA/우회/도착 이벤트를 모사 (운전석 상단 "시연 패널"로 토글).

## 구조 (`app/src/main/java/com/taxitalk`)
| 패키지 | 내용 | 요구사항 |
|---|---|---|
| `speech/` | `SttEngine`/`TtsEngine` 인터페이스, **Gleo AI(Pleos) STT/TTS SDK** 구현(우선) + android.speech·TextToSpeech 폴백, 좌석 존(`setAudioZoneId`) 출력, 오디오 덕킹 | FR-2, FR-4.3, NFR-5.1 |
| `translate/` | ML Kit 온디바이스 번역 + 언어 감지, 택시 상용문 사전 폴백 | FR-1.2, FR-2, NFR-4.1 |
| `vehicle/` | `PleosVehicleDataSource`(Vehicle SDK 2.3.2), `SimulatedVehicleDataSource`, 자동 폴백 `VehicleRepository` | FR-5, NFR-4.2 |
| `announce/` | 차량 상태 전이 → 안내 규칙, 심야할증 정책 | FR-3, FR-4, FR-6 |
| `ui/` | ViewModel(턴 기반 세션) + Compose 화면 | FR-1, FR-2.5, NFR-1 |
| `res/values*` | ko(기본)/en/ja/zh 안내·UI 문구 | NFR-5.2, NFR-6 |

## Pleos Vehicle SDK 사용 도메인
`initialize()` → capability check → get + register → unregister → `release()`
- **Gear**: D→P 전환 = 도착 → 하차 방향·소지품 안내 / P→D = 출발 → 벨트·할증 안내
- **Door**: 2열 왼쪽(차도 쪽) 문 열림 → "오른쪽 문으로 내리세요" 경고
- **Seat**: 2열 착석·안전벨트 → 출발 시/주행 중 벨트 안내
- **Odometer**: 트립 주행거리 → 승객 "운행 정보" 재확인
- **Toll**: 하이패스 음성 볼륨 구독 (참고: SDK의 Toll은 **할증 정보를 제공하지 않음** → 심야할증은 서울시 기준 시각으로 계산)

## 확인 필요 / 한계
- **권한**: `pleos.car.permission.CAR_DRIVE/CAR_DOORS/CAR_SEATS/CAR_TOLL/CAR_INFO/CAR_ENERGY` (SDK에서 추출, 에뮬레이터 설치 시 자동 허용 확인). 에뮬레이터 v2.1.0에서 Gear·Door·Seat·SeatBelt ✓, Odometer·Toll은 `ERROR_NOT_SUPPORTED_PROPERTY`.
- **STT/TTS**: Pleos에서는 표준 android.speech(Gleo 인식 서비스가 응답 없음)·TextToSpeech(엔진 없음)가 동작하지 않아 **Gleo AI SDK**(`SpeechToText`/`TextToSpeech` 3.3.1, 온디바이스)를 사용. 에뮬레이터 확인 결과:
  - TTS: 한국어·영어 ✓, 일본어·중국어 ✗ (`ON_DEVICE_ENGINE_ERROR` → 텍스트만 표시)
  - 좌석 존 출력: 승객 안내 `zoneId=1`(뒷좌석), 기사 통역 `zoneId=0`(앞좌석) ✓
  - STT: 한국어·영어 인식 ✓ (일본어·중국어 미확인). 좌석/화자 분리 API(`AmbientAiApi`)는 Gleo 자체 앱 전용이라 사용 불가
- **에뮬레이터 마이크**: `adb emu avd hostmicon` 으로 PC 마이크 연결 필요(에뮬레이터 재시작 시 다시). 블루투스 이어폰 마이크는 소리가 왜곡돼 인식이 안 되므로 내장 마이크 권장. 앱의 말하기 버튼 아래 레벨 바로 입력 여부 확인 가능.
- **ML Kit 번역 모델**: 최초 다운로드에 네트워크(및 Play 서비스) 필요. 실패 시 "오프라인 기본 문장 모드"(상용문 사전).
- **SSZ 좌석별 음장**: 하드웨어 제어는 범위 외. `Zone`으로 채널을 분리해 두었고, 실차에서는 `AndroidTtsEngine`의 출력을 CarAudioManager 오디오 존 라우팅으로 교체.
- **ETA/경로 변경**: NaviHelper SDK 미연동 — 시연 패널 이벤트로 모사 (`NaviEvent`). 다음 단계로 NaviHelper `TripInfo`/`TrafficStatus` 연동 권장.
- **주행 중 동작(DO)**: 비 DO 앱은 주차 중에만 화면 사용 가능 — 주행 중 음성 전용 동작 여부 검증 필요.

## 통역과 버튼 조작

통역은 무료 온디바이스 엔진만 사용합니다. 음성은 Pleos STT/TTS, 번역은 문구집 → ML Kit 순서입니다. `cloud/`, `CloudSpeechEngines.kt`, `CloudTranslator`의 Google Cloud 코드는 확장 계획용으로 남겨 두었고 앱에 연결되어 있지 않습니다.

| 입력 | 키 | 동작 |
|---|---|---|
| 기사 버튼 짧게 | HEADSETHOOK, MEDIA_PLAY_PAUSE, BUTTON_1, F1 | 기사 말 듣기 |
| 기사 버튼 길게 (0.5초 이상) | 위와 동일 | 손님 말 듣기 |
| 뒷좌석 버튼 | BUTTON_2, F2 | 손님 말 듣기 |

- 같은 쪽 버튼을 듣는 중에 다시 누르면 취소하고, 상대 쪽을 듣는 중이면 무시합니다. 음성 출력 중에 누르면 출력을 끊고 듣습니다.
- 운행 전에는 기사 버튼을 누르면 앞좌석에 언어 선택을 음성으로 안내하고, 뒷좌석 버튼은 무시합니다.
- 인식·번역 실패 시 손님에게는 음성으로 다시 요청합니다. 기사는 정차 중이면 문구 선택 화면이 뜨고, 주행 중이면 음성으로 알립니다.
- 버튼 키는 앱이 화면에 있을 때만 받습니다.

## 내비 연동 (NaviHelper SDK)

기본 내비 화면은 그대로 쓰고, 경로 정보만 읽어 손님 언어로 안내합니다. 남은 거리는 운행 중 10초마다 조회합니다.

| 내비 정보 | 앱 동작 | 에뮬레이터 확인 |
|---|---|---|
| 경로 시작, 남은 시간 | 남은 시간 안내 (경로당 1회) | 확인 |
| 남은 거리 2km 이하 | 남은 거리와 소지품 안내 (경로당 1회) | 확인 |
| 재탐색 완료 | 경로 변경 안내 | 이벤트 미수신 |
| 목적지 도착 | 기사 화면에만 표시 | 이벤트 미수신 (차가 움직이지 않음) |

시연 패널의 "내비 목적지" 버튼으로 경로를 요청하면 내비 화면이 앞으로 나오며, 미리보기에서 "안내 시작"을 누르면 경로가 시작됩니다.
