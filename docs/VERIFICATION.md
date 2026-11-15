# 「나이테」 e2e 검증 기록

에뮬레이터 없이 JVM(Robolectric)에서 앱의 실제 객체 그래프와 실제 화면을 띄워, 여섯 날의 시나리오를 처음부터 끝까지 돌려 본 기록이다.
기준 문서는 `docs/ARCHITECTURE.md` 1절(원칙)·5절(기대 동작)·6절(코디네이터 흐름).

- 마지막 실행: `:app:testDebugUnitTest` 81개 통과(실패 0, 건너뜀 0) · `:app:assembleDebug` 성공
- 구성: 기존 도메인·저장소 단위 테스트 37개 + e2e 44개(`app/src/test/java/com/naitae/connect/e2e/`)
- 화면 캡처: `docs/screenshots/` 34장(가상 콘솔 포함) + `docs/screenshots/fullwidth/` 34장(콘솔을 접은 전체 너비 = 실제 차의 배치)

## 1. 실행 방법

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
cd /c/naitae

./gradlew :app:testDebugUnitTest --console=plain          # 전체 81개, 캡처 68장을 다시 만든다(1분 안팎)
./gradlew :app:testDebugUnitTest --tests '*ScenarioE2eTest*'   # 시나리오만(화면 없이, 수 초)
./gradlew :app:testDebugUnitTest --tests '*ScreenshotE2eTest*' # 캡처만 다시
./gradlew :app:assembleDebug
```

- 결과 보고서: `app/build/reports/tests/testDebugUnitTest/index.html`
- 첫 실행 때 Robolectric 이 Android 14(API 34) 런타임을 Maven Central 에서 받는다(네트워크 필요, 한 번만).
- 캡처는 디버그 단위 테스트에서만 남긴다(`naitae.screenshot.dir` 속성을 `app/build.gradle.kts` 가 `testDebugUnitTest` 에만 넣는다). 릴리스 단위 테스트에서는 화면 테스트 17개가 건너뛰어진다.
- 추가한 테스트 의존성(`testImplementation`): Robolectric 4.13, Roborazzi 1.26.0, androidx.test:core 1.6.1

## 2. 검증 방식

| 항목 | 방식 |
|---|---|
| 객체 그래프 | Robolectric 이 병합된 매니페스트로 실제 `NaitaeApp` 을 만든다 → `AppContainer` → `PleosPlatform.createOrNull`(Pleos 서비스 없음) → `SimPlatform` · `JsonFileRepository`(앱 내부 파일) · `NaitaeCoordinator`. 가짜 객체는 쓰지 않는다 |
| 입력 | 차 쪽은 `SimulatorConsole`(날짜·시동·기어·도착·좌석·시각·할 말)만, 사람 쪽은 `NaitaeActions` 만 부른다. `UiTouchE2eTest` 는 실제 화면에 터치 이벤트를 넣는다 |
| 시간 | 코디네이터·시뮬레이터의 `delay` 는 모두 메인 루퍼에 걸리므로, Robolectric 의 멈춰 있는 메인 루퍼를 20ms 씩 돌려 가상 시간으로 재현한다(실제 대기 없음). 저장소의 파일 쓰기(`Dispatchers.IO`)는 끝나서 메인으로 돌아올 때까지 가상 시간을 세워 두고 기다린다 |
| 화면 | 실제 `MainActivity`(`NaitaeAppScreen` = `NaitaeRoot` + `ConsoleOverlay`)를 1920×1080 가로(mdpi, 밀도 1.0)로 띄우고 Robolectric 네이티브 그래픽으로 그려 PNG 로 남긴다 |
| 한글 | Robolectric 네이티브 런타임에 든 글꼴로 한글이 그대로 그려진다. 별도 글꼴 설정 없이 빈 네모(두부)가 없음을 캡처 68장에서 확인했다 |
| 공통 확인 | 걸음마다(20ms) 다음을 확인한다: 화면의 기어 = 차의 기어, `driving` = (기어 ≠ P), 주행 중에는 홈만 보이고 듣는 중 표시 없음, 시동이 꺼지면 자막·메모 카드 없음, 같은 자막이 12초·듣는 중 표시가 10초 넘게 남지 않음 |

검증을 위해 제품 코드에 둔 이음매는 하나다: 시뮬레이터 시계가 `System.nanoTime()` 대신 `SystemClock.elapsedRealtimeNanos()` 를 쓴다(`vehicle/sim/SimVehicle.kt`). 기기에서는 똑같이 흐르고, JVM 에서는 `delay` 와 같은 가상 시계를 따라가 "18:40", "같은 날짜를 다시 고르면 처음부터" 같은 시각 의존 동작까지 재현된다.

### 테스트 파일

| 파일 | 내용 |
|---|---|
| `E2eHarness.kt` | 앱 띄우기, 가상 시간, 화면 기록(history), 공통 확인, 캡처, 실제 터치 |
| `ScenarioWalks.kt` | 날짜별 걸음(입력 → 화면 순서 · 차 · 데이터 확인). 아래 테스트들이 함께 쓴다 |
| `ScenarioE2eTest.kt` (7) | 여섯 날 각각 + 여섯 날을 발표 순서대로 이어서 |
| `CrossCuttingE2eTest.kt` (12) | 주행 중 잠금, 끊긴 흐름의 뒷정리, 날짜 바꾸기 |
| `EdgeCaseE2eTest.kt` (8) | 곁길: 주제 없는 곳, 답 없이 출발, 홈으로, 편지 도중 시동 끔·출발, 이어받기 조명, 파일 저장, 발표 스크립트 |
| `ScreenshotE2eTest.kt` (7) · `FullWidthScreenshotE2eTest` (7) | 같은 걸음을 실제 화면과 함께 걷고 캡처(콘솔 포함 / 콘솔 접음) |
| `UiTouchE2eTest.kt` (3) | 화면에 보이는 글자를 찾아 실제 터치: 콘솔 버튼, 화면 버튼, 주행 중 잠금, 콘솔 접기 |

## 3. 시나리오별 확인 내용

| 날 | 화면 순서(확인한 그대로) | 함께 확인한 것 |
|---|---|---|
| WEEKDAY | `EngineOff` → 시동 → `Briefing` → `Home` → (회사 도착) `Home` 그대로 → 시동 끔 → 18:40 시동 → `Home` + 메모 카드 | 브리핑 5줄(미팅 · 픽업 · 엄마 메모 · 낚시 D-2 · 가족 나들이 D-3), `activeIndex` −1→5, 읽은 문장 순서, 받아 적는 글이 한 자씩 늘어 "내일 세차 예약"이 차주 메모(NextIgnition)로 저장, 브리핑 완료 기록. 회사 도착은 화면·음성·초안 없음("평소 도착" 로그). 18:40: 카드 "퇴근길에 우유 사 오기"(엄마), 음성 "엄마 메모예요. 우유 사기.", 브리핑 없음, 다시 알림 기록, 카드는 12초 뒤 사라짐, 같은 저녁 재시동에는 다시 읽지 않음 |
| FISHING_SATURDAY | 시동 → `Briefing` → `RouteAsk` → (안내해 줘) `Home` → (낚시터 도착) `Home` 그대로 → 11:40 시동 → `RecordAsk` → `RecordEdit` → (한마디) → `Home`(낚시) | 질문 뒤 8초가 지나도 길 안내는 시작되지 않음(`guidanceTo == null`, `requestRoute` 로그 없음), 수락 뒤에만 `NaviHelper.requestRoute(낚시터)`. 도착 때 초안만 준비. 초안: 낚시터 · 낚시 · 처음 간 곳 · 32km · 1석. 기록 +1, 새 점 강조, "올해 첫 감성돔"이 한마디로 붙고(말한 사람 저장 안 함) 듣는 중 표시가 꺼짐 |
| FAMILY_SUNDAY | 시동 → `Briefing` → `RouteAsk`(수목원) → `Home` → 도착 `Home` → 16:30 시동 → `RecordAsk` → `RecordEdit` → `Decorate` → `RecordEdit` → `Home`(우리 가족 여행) | 좌석 4, 초안 41km · 4석. "아빠, 내년에 또 오자!" 저장. 꾸미기: 공유받은 엄마 · 지우 · 도윤, 4색. 사진 +1. 보라를 고르면 기록의 색과 실내 조명이 PURPLE(밝기 90). 마치면 실내가 평소 설정(흰색 30)으로 돌아감 |
| ONE_YEAR_LATER | 시동 → `Briefing` → `RouteAsk` → `Home` → (수목원 도착) `RecallView` → (닫기) `Home` | 2026년 수목원 기록, `yearsAgo` 1, "1년 전 오늘, 여기서", 실내 조명 PURPLE, 그날의 한마디 낭독(`playingVoice` 켜졌다 꺼짐), 다시 듣기, 닫으면 낭독이 멈추고 조명 복원, 기록·초안 변화 없음 |
| BIRTHDAY | 시동 → `LetterArrive` → (열기) `LetterOpen` WARMING→READING→DONE → (기어 D) `Home` | 열기 전에는 실내를 건드리지 않음. 열면 시트 3단 · 핸들 열선 · 24℃ 바람 1, 조명 WHITE 0→100(10단계, 3초), 낭독 시작 뒤 글자가 한 자씩 늘어 전체 글에 이름. 출발하면 조명만 흰색 30 으로, 온기·공조는 그대로, 편지는 열어 본 것으로 기록되고 다음 시동에 다시 오지 않음 |
| TWENTY_YEARS_LATER | 시동(새 VIN) → `LetterOpen` → (닫기) `Handover` → (나이테 보기) `Home` | 이어받기: 그랜저는 보낸 차로, 새 차 등록, 기록 · 일정 · 메모 그대로, 차 이름 "새 현대차". 편지: 핸들 열선, PURPLE 0→100(4초), 나이테와 함께. `Handover`: `ringCount` 20, 주행거리 0km, 완성된 겹 20. 이어받기 화면을 닫으면 조명 복원. 재시동해도 이어받기 · 편지는 한 번뿐 |

### 공통 원칙(`CrossCuttingE2eTest`, `EdgeCaseE2eTest`, `UiTouchE2eTest`)

- 주행 중: 브리핑·메모 듣기·한마디 듣기 도중 D 로 바꾸면 자막이 바로 사라지고 홈만 남으며 뒤에서 이어지지 않는다. R · N 도 주행. 주행 중의 조작(주제 고르기, 꾸미기 열기 등)은 무시되고, 실제 터치도 잠금 덮개에 막힌다. P 로 돌아오면 다시 눌린다.
- 끊긴 흐름: 그만 → 남은 단계(길 안내 질문)로. 브리핑 도중 시동 끔 → 다음 시동에 처음부터. 편지 연출 도중 닫기 → 조명 올리기가 멈추고 편지 전 밝기로, 낭독하지 않음. 편지 낭독 중 시동 끔 → 조명 복원 · 열어 본 편지. 듣다 끊긴 말은 메모 · 한마디가 되지 않는다.
- 미루기: 편지 "도착해서 보기" → 다음 도착 뒤 P 에서 다시. 남기기 "나중에" 또는 답 없이 출발 → 기록하지 않고 다시 묻지 않는다. 새 차에 동승자가 있으면 편지를 바로 열지 않고 먼저 묻는다.
- 홈으로: 어느 화면에서든 그 화면의 닫기와 같은 뒷정리(조명 복원, 미루기, 열어 본 편지).
- 날짜 바꾸기: 같은 날짜를 다시 고르거나 흐름 도중에 다른 날짜를 고르면 흐름이 멈추고 데이터가 `SeedData.forDay(day)` 와 같아지며 초안 · 길 안내 · 미뤄 둔 편지 · 이어받기 흔적이 남지 않는다. 다시 시동을 걸면 그날의 처음부터 돈다.
- 저장: 바뀐 계정 원본이 `filesDir/naitae.json` 에 그대로 저장된다.
- 발표 스크립트: `ScenarioScript.play` 를 손대지 않고 끝까지 돌리면 날마다 기대한 화면(브리핑 → 홈, 길 안내 질문, 남길지 묻기, 다시 보기, 편지 도착, 새 차 편지)에 이른다.

## 4. 찾아서 고친 것

| # | 파일 | 증상 | 고친 내용 |
|---|---|---|---|
| 1 | `app/CoordinatorLetter.kt`, `app/NaitaeCoordinator.kt` | 새 차 첫 시동의 편지를 닫으면 실내 조명이 보라 100 으로 남고, 이어받기 화면을 닫거나 출발해도 되돌아가지 않음(편지 전 조명의 기억을 버림). 1절 "닫거나 출발하면 실내 조명만 원래대로"와 어긋남 | 이어받기 화면 동안은 보랏빛을 두되 기억을 남겨 두고, 이어받기 화면을 떠날 때(나이테 보기 · 출발 · 시동 끔) `releaseLetterLight()` 로 되돌림. 날짜를 바꿀 때는 끊긴 연출의 기억도 버림 |
| 2 | `ui/screens/frame/Frame.kt` | 상단 바의 시계 · 기어가 오른쪽 끝이 아니라 가운데에 떠 있고, 차 이름 길이에 따라 위치가 달라짐(`weight(fill = false)` 칸과 `Spacer` 가 남는 너비를 반씩 나눔) | 차 이름이 남는 너비를 모두 차지하게 해 시계 · 기어를 오른쪽 끝에 고정 |
| 3 | `ui/console/ConsoleOverlay.kt` | 1080 높이에서 "지금 값" · "시각 바꾸기(퇴근 18:40)" · "다음에 할 말"이 스크롤 아래에 숨고, 잘린 칸만 보임. 시나리오에 필요한 조작이 보이지 않음 | 누르는 것을 위로, 읽는 값을 아래로 옮기고, 넓은 패널에서는 날짜 3칸 · 목적지 4칸 · 할 말 4칸으로 놓아 모든 조작이 스크롤 없이 보이게 함 |
| 4 | `app/AppScreen.kt`, `ui/console/ConsoleOverlay.kt` | 실내 상태(`UiState.cabin`)를 어느 화면도 쓰지 않아, 시뮬레이터에서는 꾸미기의 색 · 다시 보기의 색 · 편지의 조명이 로그 말고는 보이지 않음 | 시뮬레이터일 때만 차량 화면 가장자리에 실내 조명의 색 · 밝기를 빛으로 비추고(실제 차에서는 그리지 않음), 콘솔 "지금 값"에 실내 조명 · 시트 열선 · 핸들 열선 · 공조를 표시. `ConsoleOverlay` 에 선택 매개변수 `cabin` 추가 |
| 5 | `ui/screens/LetterScreens.kt`, `ui/screens/parts/TextParts.kt` | 편지 글이 낱말 가운데에서 줄바꿈("달렸으면 좋 / 겠습니다", "아빠 / 가,") | 낱말 안의 글자를 폭 없는 문자(U+2060)로 이어 띄어쓰기에서만 줄이 바뀌게 함(`keepWordsTogether`) |
| 6 | `vehicle/sim/SimVehicle.kt` | (버그 아님, 검증용 이음매) 시뮬레이터 시계가 `System.nanoTime()` 에 묶여 가상 시간으로 재현할 수 없음 | `SystemClock.elapsedRealtimeNanos()` 로 바꿈 |

- 계약 파일(`Models.kt`, `VehicleGateways.kt`, `NaitaeRepository.kt`, `UiContract.kt`, `Theme.kt`, `Common.kt`, `domain/*.kt` 공개 시그니처)은 고치지 않았다. `vehicle/pleos/**` 도 손대지 않았다.
- 빌드: `app/build.gradle.kts` 에 테스트 의존성 3개와 `testOptions`(안드로이드 리소스 포함, 캡처 위치, 테스트 힙 3g)만 더했다.
- 로직은 1번을 빼고 처음 돌린 그대로 여섯 날의 기대 동작을 모두 통과했다.

## 5. 확인하지 못한 것(Pleos 에뮬레이터나 실제 기기에서만 볼 수 있는 것)

| 영역 | 내용 |
|---|---|
| Pleos SDK 어댑터 | `vehicle/pleos/**` 전체(Vehicle · NaviHelper · TextToSpeech · SpeechToText · FusedLocation 연결, 콜백, 권한). JVM 에서는 "Pleos 서비스 없음 → 시뮬레이터" 경로만 지났다 |
| 서비스 · 권한 | `VehicleWatchService`(화면이 없을 때의 시동 감지), 알림 · 위치 · 마이크 권한 대화상자 |
| 소리 | 실제 TTS · STT 의 박자와 오디오 포커스, 녹음 재생(`AssetVoicePlayer`: `assets/voice/*` 파일이 없어 늘 TTS 대체 경로), 시뮬레이터의 `speakAloud`(기기 합성 음성) |
| 디스플레이 | 실제 Pleos 디스플레이의 해상도 · 밀도 · 비율(캡처는 1920×1080 mdpi 기준), 시스템 바 · 인셋, 기기 글꼴 |
| 그리기 | Robolectric 은 캡처할 때만 그린다. 화면 전환(스며들기) · 조명 번짐 · 깜빡이는 막대 같은 움직임의 중간 모습, 프레임 속도(겹 20개 · 점 수백 개), 터치 반응 속도 |
| 생명주기 | 프로세스 종료 뒤 복원, 화면 회전 · 구성 변경, 릴리스 빌드(난독화 없음이지만 실행은 안 해 봄) |
| 장소 메모 | 근접 알림(`AtPlace`) 흐름: 시드 데이터에 없고 앱에서 말로 만들 방법도 없어 e2e 로는 지나지 못함(도메인 단위 테스트만 있음) |
| 차 신호의 예외 | VIN 을 못 읽는 차, 기어 UNKNOWN, 시동이 P 가 아닌 채로 걸리는 경우, 자정을 넘기는 주행 |

## 6. 고치지 않은 화면 · 동작의 아쉬운 점

화면

- 전체 너비(실제 차 배치)의 홈: 음성 자막 막대가 마지막 D-day 줄을 가리고, 메모 카드가 주제 칩과 "○○ · 올해의 겹이 자라는 중" 줄을 가린다(`fullwidth/06_weekday_evening_memo.png`). 홈이 높이를 꽉 채워 자막 자리가 없다.
- 콘솔을 편 화면(차량 화면 1356px)의 홈 · 길 안내 질문 · 남길지 묻기: 내용이 세로 가운데에 모여 위아래가 비고, 길 안내 질문은 오른쪽이 빈다. 전체 너비에서는 고르게 찬다.
- 남긴 기록 화면: 한마디와 버튼 사이가 넓게 비고, 꾸미기에서 더한 사진이 이 화면에는 보이지 않는다(색은 나이테에 반영).
- 브리핑: 자막 막대가 마지막 줄 바로 아래에 붙는다. 줄이 더 많은 날에는 마지막 줄을 가릴 수 있다.
- 주행 중: 기어 표시 말고는 잠겨 있다는 표시가 없다(설계: 안내 문구를 띄우지 않음).
- 겹이 두 개뿐인 주제(1년 뒤 우리 가족 여행): 지난해 겹이 넓은 띠로 칠해져 원판처럼 보인다.
- 가상 콘솔: 글자 14sp · 버튼 높이 56dp 로 차량 화면의 버튼보다 작다(발표자용 도구). "지금 값"은 스크롤해야 보인다.
- 시뮬레이터의 조명 번짐: 평소 설정(흰색 30)에서도 밝은 화면 가장자리에 옅은 크림색이 돈다.

동작

- 다시 보기가 열린 도착은 기록 초안을 만들지 않는다(도메인 규칙, 단위 테스트로 고정). 그래서 1년 뒤 수목원에 다시 간 날은 "오늘을 남길까요?"가 나오지 않는다. 시드 데이터에는 해마다 수목원 기록이 있어 서로 맞지 않는다.
- 시뮬레이터: 듣는 중에 "한마디 남기기"를 다시 누르면 콘솔에서 넣어 둔 말(`sayNext`)이 끊긴 듣기에 쓰여 사라진다.
- `ScenarioScript`(발표 자동 진행)와 `SimSettings.speakAloud` 는 화면에서 켤 방법이 없다.
- 다시 보기의 "몇 번째 겹", 한마디를 남긴 사람 이름은 계약에 값이 없어 표시하지 않는다(`docs/requests/ui-B.md`).

## 7. 캡처 목록

`docs/screenshots/NN_<날>_<화면>.png` (같은 이름이 `fullwidth/` 에도 있다)

| 파일 | 장면 |
|---|---|
| 01_weekday_engine_off | 시동 꺼짐 |
| 02_weekday_briefing | 브리핑, 엄마 메모 줄을 읽는 중 |
| 03_weekday_briefing_listening | "메모할 것 있으세요?" 뒤 받아 적는 중 |
| 04_weekday_home | 홈(러닝) |
| 05_weekday_driving | 주행 중(기어 D) |
| 06_weekday_evening_memo | 18:40 엄마 메모 카드와 자막 |
| 07_fishing_briefing | 토요일 브리핑 |
| 08_fishing_route_ask | "낚시터로 안내할까요?" |
| 09_fishing_arrived_quiet | 낚시터 도착 뒤(화면 변화 없음) |
| 10_fishing_record_ask | "오늘 낚시터, 어땠어요?" |
| 11_fishing_record_edit | 남긴 기록 |
| 12_fishing_record_listening | 한마디 받아 적는 중 |
| 13_fishing_record_noted | 한마디가 붙은 기록 |
| 14_fishing_home | 홈(낚시) |
| 15_family_route_ask | "수목원으로 안내할까요?" |
| 16_family_record_ask | "오늘 수목원, 어땠어요?" |
| 17_family_record_noted | 지우의 한마디가 붙은 기록(좌석 4) |
| 18_family_decorate | 함께 꾸미기 |
| 19_family_decorate_purple | 사진 1장, 보라 → 실내 조명 보라 |
| 20_family_record_decorated | 꾸민 뒤의 기록(조명 복원) |
| 21_family_home | 홈(우리 가족 여행) |
| 22_oneyear_recall | "1년 전 오늘, 여기서", 조명 보라, 그날의 목소리 |
| 23_oneyear_home | 닫은 뒤 홈 |
| 24_birthday_letter_arrive | 편지가 도착했어요 |
| 25_birthday_letter_warming | 편지 연출(조명이 밝아지는 중) |
| 26_birthday_letter_reading | 낭독 중(글자가 나타나는 중) |
| 27_birthday_letter_done | 낭독 끝 |
| 28_twenty_letter_warming | 새 차의 첫 시동(보랏빛이 밝아지는 중) |
| 29_twenty_letter_reading | 새 차 편지 낭독 중 |
| 30_twenty_letter_done | 새 차 편지 낭독 끝 |
| 31_twenty_handover | "새 차 0km · 나이테 스무 겹" |
| 32_twenty_home | 홈(스무 겹, 조명 복원) |
| 33_weekday_record_ask_topic_choice | 주제가 없는 처음 간 곳(마트): 주제 고르기 |
| 34_weekday_home_golf | 홈에서 다른 주제(골프) 고르기 |

## 8. 테스트를 고칠 때 알아 둘 것

- Robolectric 의 Choreographer 는 멈춰 두고(`ShadowChoreographer.setPaused(true)`) 16ms 프레임으로 돌린다. 기본값은 프레임마다 시계를 당겨 `delay` 가 한꺼번에 지나간다.
- Compose 는 프레임 시계를 프로세스에서 한 번만 만들어 첫 테스트의 Choreographer 를 붙잡는다. 테스트마다 새 Choreographer 로 만든 디스패처를 `WindowRecomposerPolicy` 로 넘긴다(`E2eHarness.useThisTestsFrameClock`). 빼면 두 번째 테스트부터 화면이 첫 장면에서 멈춘다.
- Robolectric 은 프레임마다 그리지 않으므로 Compose 의 배치가 밀린다. 화면 상태가 바뀔 때와 누를 곳을 찾기 전에 `measureAndLayoutForTest()` 로 배치를 마친다.
- 캡처(Espresso) 전에 예약된 화면 갱신을 프레임을 흘려 비운다(`flushTraversals`). 남아 있으면 캡처가 끝나지 않는다.
- 저장이 끝났는지는 `JsonFileRepository.mutex` 를 리플렉션으로 본다. 필드 이름을 바꾸면 `E2eHarness` 도 고쳐야 한다.
