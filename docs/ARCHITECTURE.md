# 「나이테」 Pleos Connect 프로토타입: 구조와 작업 규칙

기획 원문: `C:\경진대회\현대차그룹 차량용 앱 개발 공모전\제출본\나이테_접수원고_v27.md` (3·4·5번 칸이 기능 정의)
화면 참고(HTML 목업): `C:\경진대회\현대차그룹 차량용 앱 개발 공모전\제출본\목업\나이테_차량화면_목업.html`
SDK 실제 시그니처: `docs/pleos-sdk-api/*.api.txt` (javap 추출), 플랫폼 조사 노트: `docs/PLEOS_NOTES.md` (있으면 읽을 것)

## 1. 앱이 하는 일 (v27 요약)

차에서 쓰는 일정·메모 + 특별한 날의 추억 기록 앱. 다섯 동사:

| 동사 | 기능 | 동작 |
|---|---|---|
| 챙긴다 | 일정·메모 브리핑 | 하루 첫 시동(P)에 오늘 일정·메모·주제별 일정 D-day를 음성으로 읽음. 끝에 "메모할 것 있으세요?"라고 묻고 말로 메모를 받음(화면 입력 없음). 일정에 장소가 있으면 "낚시터로 안내할까요?"라고 **묻고**, 답하면 길 안내(자동 시작 금지). 퇴근길 시동에는 정해 둔 메모를 다시 알려 줌. 장소 메모는 그곳에 가까워지면 알려 줌 |
| 남긴다 | 특별한 날 기록 · 함께 꾸미기 | 도착할 때는 **묻지 않음**. 그곳에서 시간을 보낸 뒤 돌아가려 시동을 걸면(P) "오늘을 남길까요?". 주행거리·처음 간 곳·동승 여부는 차가 채우고, 사람은 한마디(음성)·사진·색만 더함. 고른 색은 실내 조명이 됨 |
| 돌아본다 | 차에서 다시 보기 | 같은 장소에 다시 오면 "1년 전 오늘, 여기서"가 열리고 그날의 색으로 실내 조명이 바뀌며 그날의 목소리가 재생됨 |
| 건넨다 | 미래 편지 | 정한 날 시동을 걸면 출발 전에 열림. 시트·핸들 온기, 실내 조명이 서서히 밝아짐, 낭독. 닫거나 출발하면 실내 조명만 원래대로 |
| 이어진다 | 이어받기 | 새 차(VIN이 다름)에서 계정의 나이테·일정·메모를 그대로 이어받음. "새 차 0km · 나이테 스무 겹" |

원칙: 앱이 매일 화면을 띄우지는 않는다(평소 도착엔 화면 없음). 주행 중(기어 P가 아님)에는 화면 입력을 받지 않고 음성만 쓴다. 동승자가 누구인지는 저장하지 않는다(좌석 수만). 나이테 단위는 1년 = 한 겹, 기록 = 그해 겹의 점.

## 2. 기술 구성

- Kotlin 1.9.25, AGP 8.5.2, Gradle 8.9, JDK 21(타깃 17), compileSdk/targetSdk 34, minSdk 28
- Jetpack Compose (BOM 2024.06.00, 컴파일러 확장 1.5.15), Material3, kotlinx-serialization-json 1.6.3, coroutines 1.8.1
- Pleos Connect SDK: `ai.pleos.playground:Vehicle:2.3.2`, `NaviHelper:3.0.5`, `TextToSpeech:3.3.1`, `SpeechToText:3.3.1`, `FusedLocation:1.1.3`
- 단일 모듈 `app`, 패키지 `com.naitae.connect`, 단일 액티비티(가로 화면), 화면 전환은 `UiState.screen` 값으로만 한다(내비게이션 라이브러리 없음)
- DI 프레임워크 없음(`AppContainer`가 직접 조립). Room·Hilt·KSP 쓰지 않음

```
com.naitae.connect
├─ core/model      Models.kt                     [계약] 데이터 모델
├─ vehicle         VehicleGateways.kt            [계약] 차량 연결 인터페이스, SdkLog, SimulatorConsole
│   ├─ pleos       PleosPlatform + 어댑터          실제 Pleos SDK 구현
│   └─ sim         SimPlatform + 시뮬레이터        가상 콘솔 구현
├─ data            NaitaeRepository.kt [계약], JsonFileRepository, SeedData
├─ domain          Briefing/Memos/Trips/Records/Recall/Letters/Handover/Rings/Schedules  [계약: 공개 시그니처]
├─ ui              UiContract.kt [계약] Screen · UiState · NaitaeActions
│   ├─ theme       Theme.kt [계약]
│   ├─ components  Common.kt [계약], Rings.kt, SceneIllustration.kt
│   ├─ screens     NaitaeScreens.kt(NaitaeRoot) + 화면별 파일
│   └─ console     ConsoleOverlay.kt (시뮬레이터 전용 가상 콘솔)
└─ app             NaitaeApp, MainActivity, AppContainer, NaitaeCoordinator, 서비스
```

## 3. 작업 규칙 (모든 담당 공통)

1. **자기 담당 폴더의 파일만 만들고 고친다.** 담당표는 4절.
2. **[계약] 파일은 고치지 않는다.** 이미 있는 빈 틀(stub)의 공개 시그니처(이름·매개변수·반환형)는 그대로 두고 본문만 채운다. 새 파일·새 private/internal 함수는 자유. 계약을 꼭 바꿔야 하면 고치지 말고 `docs/requests/<담당>.md`에 이유와 원하는 변경을 적고, 지금 계약으로 최선의 구현을 한다.
3. **Gradle을 실행하지 않는다.** 여러 담당이 같은 폴더에서 동시에 작업하므로 빌드는 총괄이 모아서 돌린다. 대신 코드를 꼼꼼히 다시 읽어 컴파일 오류(임포트 누락, 이름 불일치, 널 처리)를 줄인다.
4. 새 라이브러리를 쓰지 않는다. 꼭 필요하면 `docs/requests/<담당>.md`에 적는다.
5. Kotlin 1.9 문법, 명시적 임포트(와일드카드 금지), 실험적 API는 꼭 필요할 때만 `@OptIn`과 함께.
6. 화면 글자는 한국어. 주석도 한국어로, '왜'가 필요한 곳에만. 실존 인물·회사 이름과 다른 앱 이름을 쓰지 않는다. 데이터는 모두 가상 페르소나(아빠, 엄마, 딸 지우 10세, 아들 도윤 6세, 그랜저).
7. 실패에 안전하게: SDK 호출은 모두 예외를 잡아 `Result`/null로 돌려주고 앱이 죽지 않게 한다. Pleos 서비스가 없는 기기에서도 앱은 시뮬레이터로 실행된다.
8. SDK를 부르거나 이벤트를 받을 때마다 `SdkLog.api("Vehicle.getCurrentDistanceDriven() → 12,840km")`, `SdkLog.event(...)`, `SdkLog.sys(...)`로 한 줄 남긴다(가상 콘솔에 표시되어 기술 활용을 보여 줌).
9. 끝나면 최종 메시지에: 만든 파일 목록, 계약과 다르게 한 점, 다른 담당이 알아야 할 점, 스스로 의심스러운 컴파일 위험 지점을 적는다.

## 4. 담당표

| 담당 | 맡는 파일 | 하는 일 |
|---|---|---|
| pleos | `vehicle/pleos/**`, `docs/PLEOS_MANIFEST_SNIPPET.xml` | 실제 SDK 어댑터, 필요한 매니페스트 권한 목록 |
| sim | `vehicle/sim/**` | 시뮬레이터와 가상 콘솔 |
| domain | `data/**`, `domain/**`, `app/src/test/**` | 저장소, 가상 데이터, 엔진 로직, 단위 테스트 |
| ui-A | `ui/components/Rings.kt`, `ui/components/SceneIllustration.kt`, `ui/screens/NaitaeScreens.kt`, `HomeScreen.kt`, `BriefingScreen.kt`, `RouteAskScreen.kt`, `ui/console/**`, `ui/screens/frame/**` | 나이테·장면 그림, 공통 틀, 홈·브리핑·길 안내 질문, 가상 콘솔 |
| ui-B | `ui/screens/RecordScreens.kt`, `RecallScreen.kt`, `LetterScreens.kt`, `HandoverScreen.kt`, `ui/screens/parts/**` | 기록·꾸미기·다시 보기·편지·이어받기 화면 |
| app | `app/**`(패키지), `AndroidManifest.xml`, `res/**` | 조립, 코디네이터(상태 기계), 서비스, 미디어 재생 |

## 5. 시나리오와 가상 데이터 (domain·sim·app 공통 기준)

장소(`SeedData`): 회사, 낚시터, 수목원, 마트. 차: 그랜저 `VIN_FIRST_CAR`, 20년 뒤 새 차 `VIN_NEW_CAR`.
구성원: owner 아빠(OWNER), mom 엄마(FAMILY), jiwoo 지우(CHILD), doyun 도윤(CHILD).
주제: work 본업(WORK, 회사), fish 낚시(FISHING, 낚시터), run 러닝(RUNNING), golf 골프(GOLF), family 우리 가족 여행(FAMILY_TRIP, 수목원, mom·jiwoo·doyun에게 공유).

| ScenarioDay | 날짜·시각 | 그날 아침까지의 데이터(`SeedData.forDay`) | 기대 동작 |
|---|---|---|---|
| WEEKDAY | 2026-11-12(목) 07:42 | 일정: 10:00 거래처 미팅(휴대폰 캘린더), 19:00 지우 피아노 학원 픽업. 메모: 엄마 "퇴근길에 우유 사 오기"(EveningIgnition 17시). 다가오는 주제별 일정: 11-14(토) 05:00 낚시(낚시터), 11-15(일) 가족 나들이(수목원). 올해 낚시·러닝·골프 기록 몇 건 | 첫 시동 → 브리핑(미팅, 픽업, 엄마 메모, 낚시 D-2, 가족 나들이 D-3) → "메모할 것 있으세요?" → "내일 세차 예약" 메모. 회사 도착은 평소 도착(화면 없음). 18:40 시동 → "엄마 메모예요. 우유 사기." |
| FISHING_SATURDAY | 2026-11-14(토) 05:03 | 위 + 목요일 브리핑 끝남 | 첫 시동 → 브리핑(오늘 05:00 낚시) → "낚시터로 안내할까요?" → 안내 → 낚시터 도착(처음 간 곳, 화면 없음) → 11:40 다시 시동 → "오늘을 남길까요?" → "올해 첫 감성돔" |
| FAMILY_SUNDAY | 2026-11-15(일) 10:20 | 위 + 토요일 낚시 기록. 좌석 4 | 브리핑 → "수목원으로 안내할까요?" → 도착 → 16:30 다시 시동 → 남기기 → 지우 "아빠, 내년에 또 오자!" → 꾸미기(사진, 보라 → 실내 조명 보라) |
| ONE_YEAR_LATER | 2027-11-15 10:31 | 위 + 수목원 기록(지우의 한마디, 색 PURPLE, 사진). 오늘 일정: 가족 나들이(수목원). 좌석 4 | 수목원 도착 → "1년 전 오늘, 여기서", 실내 조명 보라, 작년 목소리 |
| BIRTHDAY | 2027-11-23 07:38 | 위 + 미래 편지(엄마·지우·도윤 → 아빠, 오늘 열림, WHITE 100, 3초, 시트 3단, 핸들 열선, 24℃ 바람 1) "아빠, 생일 축하해! 오늘 출근길도 조심히 다녀와요." | 시동 → 편지가 도착했어요 → 열기 → 연출·낭독 → 출발하면 실내 조명만 원래대로 |
| TWENTY_YEARS_LATER | 2046-11-15 11:05 | 20년치 기록(해마다 여러 건), 그랜저가 현재 차로 등록됨, 편지(지우·도윤, openOnNewCar, PURPLE 100, 4초, 핸들 열선) "늘 나를 지켜 주는 우산인 줄 알았던 아빠가, 이제는 빗길 없이 따뜻하게만 달렸으면 좋겠습니다." 시뮬레이터 VIN = `VIN_NEW_CAR`, 주행거리 0km | 첫 시동 → 이어받기(조용히) → 핸들 온기·보랏빛이 서서히 → 편지 낭독 → "새 차 0km · 나이테 스무 겹" |

## 6. 코디네이터 흐름 (app 담당이 구현, 다른 담당은 이 흐름에 맞춘다)

시동 ON(P)일 때 위에서부터 처음 맞는 것 하나:
1. 등록된 차가 없으면 지금 VIN을 첫 차로 등록(`HandoverEngine.registerFirstCar`).
2. VIN이 다르면 이어받기(`detect` → `apply`). 이어서 `LetterEngine.due(newCarFirstIgnition = true)`가 있으면 바로 `LetterOpen`(동승자가 있으면 `LetterArrive`), 편지를 닫으면 `Handover` 화면. 편지가 없으면 바로 `Handover`.
3. `TripTracker`가 `AskRecord`를 내면 `RecordAsk`.
4. 오늘 열 편지가 있으면 `LetterArrive`.
5. `BriefingEngine.shouldBrief`면 `Briefing`: 인사 → 항목을 하나씩 읽으며 `activeIndex` 증가 → "메모할 것 있으세요?" → `VoiceInput.listen` → 말이 있으면 `MemoEngine.add` → `markDone`. 끝나면 6으로.
6. `MemoEngine.dueAtIgnition`이 있으면 메모 카드 + 낭독.
7. `TripTracker`가 `AskRoute`를 내면 `RouteAsk`("○○로 안내할까요?"를 음성으로도 물음), 아니면 `Home`.

그 밖: 기어가 P가 아니게 되면 `driving = true`, 낭독·연출을 멈추고(편지는 `LetterPerformer.finish()`), 입력 화면은 닫고 `Home`. 도착 이벤트 뒤 P가 되면 `TripTracker` 결과에 따라 조용히 지나가거나(`QuietArrival`, `DraftReady`) `RecallView`를 연다. 근접 이벤트가 오면 `MemoEngine.dueAtPlace` 메모를 카드 + 낭독. 시동 OFF면 `EngineOff`. 읽는 문장은 `UiState.voiceLine`에 넣어 화면 아래 자막으로 보이게 한다.
