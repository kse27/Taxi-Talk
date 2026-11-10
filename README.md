# Taxi-Talk

**버튼 하나로 택시 기사(한국어)와 외국인 승객(영어·일본어·중국어)의 대화를 좌석별로 통역하고, 차량 데이터로 필요한 안내를 승객 언어로 먼저 알려 주는 Pleos 차량용 앱입니다.**

- 기사는 운전석 버튼, 승객은 뒷좌석 버튼을 누르고 말하면 상대방 자리 스피커로 통역 음성이 나갑니다.
- 착석·안전벨트·뒷문·기어(Pleos Vehicle SDK)와 목적지·남은 거리(NaviHelper SDK)를 읽어 벨트 착용, 남은 시간, 소지품, 차도 쪽 하차 경고를 승객 언어로 안내합니다.
- 기사 화면은 정차(P) 중에는 대화·승객 상태·안내 기록을, 주행 중에는 최근 통역과 버튼만 보여 줍니다.
- 대화는 서버로 보내지 않고 운행 중에만 기기에 보관하며, 운행을 종료하면 삭제합니다.

---

## 1. 실행 방법

### APK로 바로 실행

1. Pleos Connect 에뮬레이터를 실행합니다.
2. APK를 설치합니다. `-g`는 마이크 권한을 함께 허용합니다.
   ```bash
   adb install -r -g apk/A2L_TaxiTalk_release.apk
   ```
3. 앱 목록에서 **Taxi-Talk**를 실행하고, 시작 화면에서 손님 언어(영어·일본어·중국어)를 고르면 운행이 시작됩니다.

### 소스에서 빌드

```bash
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

### 사용 방법

| 입력 | 동작 |
|---|---|
| 화면 `내가 말하기` / 운전석 버튼 짧게 | 기사의 한국어를 승객 언어로 통역해 뒷좌석으로 출력 |
| 화면 `손님 말 듣기` / 운전석 버튼 길게(0.5초 이상) / 뒷좌석 버튼 | 승객의 말을 한국어로 통역해 운전석으로 출력 |
| `빠른 발화` 문구 (정차 중) | 자주 쓰는 문장을 눌러 바로 승객에게 전달 |
| `운행 종료` 두 번 | 운행을 끝내고 대화 내용 삭제 |

- 에뮬레이터에서는 키보드 이벤트로 물리 버튼을 대신할 수 있습니다. 운전석 버튼은 `adb shell input keyevent KEYCODE_F1`, 뒷좌석 버튼은 `adb shell input keyevent KEYCODE_F2`입니다. 블루투스 버튼의 `HEADSETHOOK`, `MEDIA_PLAY_PAUSE`, `BUTTON_1`, `BUTTON_2`도 받습니다.
- 버튼 키는 앱이 화면에 떠 있을 때만 받습니다.

### 차량 상황 재현 (시연 패널)

차량 상태는 에뮬레이터 **Virtual Console**에서 바꾸면 SDK 콜백으로 그대로 반영됩니다. 앱 상단의 `시연 패널`을 열면 같은 상황을 버튼으로 재현할 수 있습니다.

| 시연 패널 | 확인할 수 있는 안내 |
|---|---|
| `모사 모드 강제` | 실제 차량 데이터 대신 모사 데이터를 사용 |
| `뒷좌석 착석` → `기어 D` | 벨트를 안 맨 채 출발하면 승객 언어로 벨트 착용 안내 |
| `왼쪽 뒷문(차도)` | 차도 쪽 문이 열리면 즉시 하차 경고 |
| `내비 목적지: 서울역` / `근처` | 내비에 경로 요청 → 남은 시간 안내, 2km 이내면 소지품 안내 |
| `기사 인식 실패` / `손님 인식 실패` | 인식 실패 시 문구 선택 화면 / 음성 재요청 |
| `심야 시간` | 서울시 기준 심야 할증 안내 |

- 시연 패널은 기어가 P일 때만 보입니다. 모사 모드에서 기어 D로 바꾸면 화면 아래에 P로 되돌리는 버튼만 남습니다.
- 내비 목적지를 요청하면 내비 화면이 앞으로 나옵니다. Taxi-Talk를 다시 열면 안내가 이어집니다.

---

## 2. 테스트 환경 및 필요한 설정

### 테스트 환경

| 항목 | 버전 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (Android 14, API 34, arm64) |
| 개발 도구 | Android Studio (내장 JBR, JDK 17 이상) |
| 빌드 | Gradle 9.3.1 wrapper, AGP 8.13.2, Kotlin 2.3.20 |
| SDK | compileSdk·targetSdk 34, minSdk 28 |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, SpeechToText 3.3.1, TextToSpeech 3.3.1 |
| 번역 | Google ML Kit Translate 17.0.3 (온디바이스) + 택시 상용문 문구집 |

### 필요한 설정

- **API 키 없음:** 외부 유료 API를 쓰지 않습니다. 음성 인식·합성은 Pleos 온디바이스 엔진, 번역은 ML Kit 온디바이스 모델을 사용합니다.
- **네트워크(최초 1회):**
  - 빌드할 때 Pleos SDK를 `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 내려받습니다. `settings.gradle.kts`에 이미 등록되어 있습니다.
  - 앱을 처음 실행할 때 ML Kit 번역 모델을 내려받습니다. 상단에 `번역 준비됨`이 뜨면 준비가 끝난 것이고, 모델이 없으면 문구집만으로 동작합니다.
- **권한:**
  - 마이크(`RECORD_AUDIO`)는 설치할 때 `-g`로 허용하거나, 처음 실행할 때 허용합니다.
  - Pleos 권한(`CAR_DRIVE`, `CAR_DOORS`, `CAR_SEATS`, `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `STT_SERVICE`, `TTS_SERVICE` 등)은 에뮬레이터에서 자동으로 허용됩니다.
- **마이크 입력:** 에뮬레이터에 PC 마이크를 연결해야 음성 인식이 동작합니다. 에뮬레이터를 다시 시작하면 다시 연결해야 합니다.
  ```bash
  adb emu avd hostmicon
  ```

### 알려진 제한

- 에뮬레이터 v2.1.0은 Gear·Door·Seat·SeatBelt를 지원하고, Odometer·Toll은 지원하지 않습니다.
- 에뮬레이터 TTS는 한국어·영어만 동작합니다. 일본어·중국어는 화면에 텍스트로 표시합니다.
- 내비의 재탐색·도착 이벤트는 에뮬레이터에서 수신되지 않아 시연 패널로 재현합니다.

---

## 3. APK

- **파일 위치:** [`apk/A2L_TaxiTalk_release.apk`](apk/A2L_TaxiTalk_release.apk)
- **버전:** 0.1.0 (versionCode 1), release 서명
- **지원 ABI:** arm64-v8a, x86_64
