# PLEOS_ADAPTER_REVIEW — 실제 Pleos SDK 어댑터 검수 결과

- 검수일: 2026-11-15
- 대상: `app/src/main/java/com/naitae/connect/vehicle/pleos/**` (PleosPlatform · PleosSupport · PleosSignals · PleosCabin · PleosNavi · PleosVoice · PleosProximity), `docs/PLEOS_MANIFEST_SNIPPET.xml`
- 방법: 빌드·실행 없이 코드를 읽고 실제 AAR 과 대조. `docs/pleos-sdk-api/*.api.txt`(공개 시그니처)와, Nexus 에서 받은 AAR(Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1, FusedLocation 1.1.3)의 `javap -v -c` 바이트코드(어노테이션·상수·분기)를 근거로 삼음
- 한계: Gradle 을 돌리지 않았고 Pleos 에뮬레이터도 없음. 고친 곳은 컴파일·동작 모두 미검증 → 6절(컴파일 위험 지점)과 4절(에뮬레이터 점검표)로 확인 필요

## 0. 요약

| 구분 | 내용 |
|---|---|
| 고친 것 | 9건(중간 6, 낮음 3). 모두 `vehicle/pleos/**` 안, 공개 시그니처 변경 없음 |
| 그대로 둔 것 | 14건. 계약(파일) 변경이 필요하거나 에뮬레이터 없이는 옳고 그름을 가릴 수 없는 항목 |
| 가장 큰 한계 | **시동(ignitionOn)**: SDK 에 시동 API 가 없음. 검수 뒤 운전석 착좌를 탑승 신호로 쓰도록 바꿈(3절, D 안 채택) |
| 노트와 어댑터가 어긋난 곳 | 앰비언트 설정 권한은 어댑터가 맞음(`CONTROL_CAR_LIGHTS` 를 실행 중에 검사). `onRoutingCompleted` 의 뜻은 어댑터가 틀렸을 가능성이 높아 중립으로 바꿈(1절) |
| 매니페스트 | 변경 필요 없음. 어댑터가 부르는 API 의 권한 13개가 모두 선언되어 있고 `RECORD_AUDIO` 는 필요 없음(5절) |

## 1. 바이트코드로 확인한 SDK 동작

노트(`PLEOS_NOTES.md`)와 어댑터가 어긋나거나, 노트에 없던 동작만 적음.

| # | 확인한 사실 | 근거(AAR 클래스) | 어댑터에 주는 영향 |
|---|---|---|---|
| 1 | Vehicle 의 권한 검사는 **구현 클래스**(`…api.implement.XxxImpl`)의 `@RequiresCustomPermission` 을 실행 중에 읽어 `checkSelfPermission` 으로 함(스택에서 메서드 이름을 찾고 kotlin-reflect 로 어노테이션 조회). `LightImpl.setAmbientLightBrightness / BrightnessColor / Switch` 는 `CONTROL_CAR_LIGHTS`. 인터페이스 `Light` 쪽 어노테이션이 `CAR_LIGHTS` 라서 노트 0절 4번이 그렇게 적힘 | `vehicle.utils.f`, `LightImpl` | **어댑터 작성자가 맞음.** 두 권한 모두 선언되어 있어 문제없음 |
| 2 | 권한이 없을 때: get·set·capability 는 `onFailed(SecurityException("<메서드> need Permission"))`(난독화된 VehicleException 이 아님). **register·unregister 는 `SecurityException` 을 바로 던짐** | `vehicle.service.b` | 어댑터는 등록을 `safeSdk`, 조회·설정을 `awaitSdk` 로 감싸 둘 다 잡음 → 문제없음 |
| 3 | Vehicle 서비스 상태는 CONNECT / CONNECTING / DISCONNECT. **CONNECTING 중의 호출은 실패가 아니라 SDK 안의 큐에 쌓였다가 연결되면 실행**됨(콜백은 그때 옴). 바인딩은 0.5초 간격 30번(약 15초) 시도 후 포기 → DISCONNECT → 이후 호출은 즉시 `ERROR_SERVICE_CONNECTION_FAILURE`, **스스로 다시 붙지 않음** | `vehicle.service.a` | F-02 로 수정. 큐에 쌓인 set 은 어댑터의 시간 초과 뒤에도 나중에 실행될 수 있음(L-06) |
| 4 | 서비스가 죽었다 살아나면(재연결) SDK 가 등록해 둔 리스너를 스스로 다시 등록함. `release()` 는 리스너를 모두 해제하고 unbind | `vehicle.service.b.e()`, `.j()` | 재연결 뒤 리스너 재등록 코드는 필요 없음 |
| 5 | Vehicle 리스너·조회 콜백, NaviHelper 이벤트, TTS·STT 이벤트, 근접 알림 콜백은 모두 **바인더 스레드**에서 직접 불림(메인으로 넘기지 않음) | `vehicle.callback.a`, `navi.helper.callback.b/c`, `tts.service.TtsCallback`, `stt.service.SttCallback` | 어댑터 상태는 StateFlow · `@Volatile` · CompletableDeferred 라 안전. `SdkLog.add` 만 원자적이지 않음(L-09) |
| 6 | `NaviHelperEventListener.onRouteEnded` 의 사용 중단 메시지가 **"Use onRoutingCompleted() instead"**. navi-data 에 `isRouting`(안내 중) 필드가 있어 routing = 안내를 뜻함 | `NaviHelperEventListener`(kotlin.Deprecated) | `onRoutingCompleted` 는 '경로 탐색 완료'가 아니라 '안내 끝'일 가능성이 높음 → F-03 |
| 7 | NaviHelper `initialize(onConnected, onFailed)` 의 두 콜백은 **한 번만** 불림. 바인딩 0.5초 × 30번 실패 뒤 `onFailed`(Dispatchers.Default 스레드). `addListener` 는 중복 등록을 걸러 줌. 요청·조회 API 는 권한이 없으면 `SecurityException` 을 던짐 | `navi.helper.manager.a/b/c`, `utils.c` | 어댑터의 try/catch · `safeSdk` 로 충분 |
| 8 | TTS·STT 의 모든 공개 메서드는 먼저 `TTS_SERVICE` / `STT_SERVICE` 를 `checkSelfPermission` 하고 없으면 `SecurityException`. **STT AAR 어디에도 `RECORD_AUDIO` 문자열이 없음** | `tts.api.ApiChecker`, `stt.api.ApiChecker` | 노트가 맞음. 마이크 권한을 선언·요청할 필요 없음 |
| 9 | ON_DEVICE TTS·STT 는 speak · stop · setLocale · request 를 `Dispatchers.Default.limitedParallelism(1)` 에서 순서대로 실행 | `tts.factory.OnDeviceStreamTts`, `stt.factory.OnDeviceStt` | stop() 직후의 speak() 이 뒤집혀 끊기는 일은 없음 |
| 10 | STT 의 `onStartedRecognition` / `onEndedRecognition` 은 서비스의 `UpdateEpdState`(말 시작·끝 감지), 최종 결과 `onUpdated(…, true)` 는 `UpdateSttCompleted` 로 서로 다른 이벤트. 순서는 AAR 로 알 수 없음 | `stt.service.SttCallback` | 말 끝 이벤트가 먼저 오면 최종 결과를 놓침 → F-04 |
| 11 | `FusedLocation.isSupported()` 는 위치 서비스 패키지 설치 여부만 봄. `addProximityAlert` / `removeProximityAlert` 는 `FUSED_LOCATION` 이 없으면 `SecurityException`, 잘못된 요청은 `onError`. `release()` 가 남은 알림을 모두 지움 | `location.fused.FusedLocation`, `service.b` | 어댑터가 호출 전에 `checkSelfPermission` 을 함 → 문제없음 |
| 12 | `IgnitionState` enum(LOCK/OFF/ACC/ON/START)은 있지만 이를 쓰는 공개 API 가 없음 | `Vehicle.api.txt` 전체 검색 | 시동은 추정할 수밖에 없음(3절) |

확인 결과 문제없던 항목(수정 없음): `RouteInfo` 는 이름 붙인 인자로 위도 → 경도(`PleosNavi.kt:150`). 도착 거리 m → km, 시간 초 → 분 반올림(`PleosNavi.kt:309-310`). 앰비언트 색은 `Long` ARGB 를 `toInt()` 로 32비트 그대로 전달(`PleosCabin.kt:94`). 밝기 0~100% 는 차의 `AreaBounds` 로 환산. 온도는 `TemperatureUnit.CELSIUS` 로 0.5℃ 단위·범위 안. 시트 열선 0~3단은 차의 최대 단계로 환산. 모든 제어 전에 `check…Capabilities` 로 접근 권한(WRITE)·영역·범위 확인. TTS·STT 는 `Mode.ON_DEVICE` + `setLocale(Locale.KOREA)`. 모든 SDK 호출에 시간 초과(기본 4초)와 예외 차단이 있고, `createOrNull` 은 `Throwable` 을 잡아 null 을 돌려줌.

## 2. 발견 사항

심각도: 높음(기능이 동작하지 않음) · 중간(특정 상황에서 틀린 결과) · 낮음(견고성·로그) · 정보

### 2.1 고친 것

| ID | 심각도 | 위치 | 무엇이 문제였나 | 어떻게 고쳤나 |
|---|---|---|---|---|
| F-01 | 중간 | `PleosSignals.kt:97-100`, `:161-178` | `start()` 가 연결 확인 때 읽어 둔 기어 값(`session.firstGear`)을 그대로 적용. `stop()` 뒤 다시 `start()` 하면(화면이 뒤로 갔다 옴) **옛 기어 값**으로 시작하고, 부르지도 않은 `Gear.getCurrentGearSelectionState()` 를 불렀다고 로그에 남김 | 리스너를 먼저 등록한 뒤 `readGear()` 로 매번 새로 읽음. 로그는 실제 호출 결과만 남김. 읽기 실패 + 값이 아직 없을 때만 연결 확인 값을 씀 |
| F-02 | 중간 | `PleosSupport.kt:128-135` | 1절 3번: SDK 가 바인딩을 포기한 뒤에는 조회를 아무리 다시 해도 `ERROR_SERVICE_CONNECTION_FAILURE` 만 돌아옴. 어댑터는 조회만 반복해 서비스가 늦게 뜨면 끝내 연결되지 않고 시동 ON 도 나가지 않음 | 그 오류를 받으면 `Vehicle.initialize()` 를 다시 불러 바인딩을 재개(로그 `Vehicle.initialize() · 서비스 바인딩 다시 시도`). 시도 횟수 20번 제한은 유지 |
| F-03 | 중간 | `PleosNavi.kt:68-73` | `onRoutingCompleted` 를 '안내 시작'으로 처리. 1절 6번대로 '안내 끝'이라면 도착 직후 `GuidanceStarted("")` 가 나가고 `guiding` 이 참으로 남아 다음 안내 시작을 알리지 않음 | 안내 시작 알림을 뺌(안내 시작은 `onRouteStarted` 로만). 기다리던 경로 요청을 성공으로 끝내는 것만 남김(두 해석 모두에서 안전) |
| F-04 | 중간 | `PleosVoice.kt:225-229`, `:268-273`, `:299-313` | ① `onEndedRecognition` 이 오면 그때까지의 부분 인식으로 바로 끝냄 → 최종 결과가 뒤따르면 버려짐. ② 앞선 듣기를 닫을 때(`stop()`) 뒤늦게 온 종료 이벤트가 바로 다음 듣기를 "말 없음"으로 끝낼 수 있음 | ① 종료 이벤트 뒤 최종 결과를 1.5초 더 기다림(`END_GRACE_MS`). ② 이번 듣기의 이벤트(onReady · onStartedRecognition · onUpdated)를 받은 뒤의 종료 이벤트만 인정. 완료 결과가 먼저 오면 지금처럼 즉시 끝남 |
| F-05 | 중간 | `PleosProximity.kt:89-93`, `:118-121` | 알림 ID 를 코루틴이 이어진 뒤에야 적어 둠 → 등록 직후 오는 전이(이미 반경 안)를 "모르는 알림"으로 버림. 등록 응답이 시간 초과와 엇갈리면 주인 없는 알림이 남음 | `onRegistered` 콜백 안에서 먼저 적고, 실패로 끝날 때는 방금 적힌 알림을 지움 |
| F-06 | 중간 | `PleosCabin.kt:390`, `:423-467` | `state` 의 앰비언트 색·밝기를 연결 때 한 번만 읽음. 앱은 `state` 를 기억했다가 되돌리므로, 그 뒤 사용자가 차에서 바꾼 조명이 아니라 **앱을 켰을 때의 조명**으로 되돌아감 | `registerAmbientLightBrightnessListener` · `registerAmbientLightBrightnessColorListener` 를 연결당 한 번 등록해 `state` 를 갱신. 로그 `Light.onBrightnessUpdated → 40%`, `Light.onBrightnessColorUpdated → #… (색)`. 해제는 `Vehicle.release()` 가 일괄 처리 |
| F-07 | 낮음 | `PleosPlatform.kt:113-123` | Pleos 여부를 액션(`VEHICLE_START`) 조회로만 판단. 서비스가 그 액션 필터를 선언하지 않았다면 Pleos 기기인데 시뮬레이터로 넘어감 | SDK 가 실제로 바인딩하는 컴포넌트(`ai.pleos.playground.service.vehicle/.VehicleService`)로도 한 번 더 확인(둘 중 하나라도 있으면 Pleos) |
| F-08 | 낮음 | `PleosProximity.kt:38`, `:45-48` | 계정 데이터가 바뀔 때마다 `isSupported()` 를 부르고 로그를 남겨 200줄 로그를 밀어냄 | 결과를 한 번만 확인해 기억(패키지 설치 여부라 실행 중 바뀌지 않음) |
| F-09 | 낮음 | `PleosNavi.kt:128-129` | 연결 중에 `stop()` 하면 기다리던 요청이 6초 시간 초과까지 붙잡힘 | `stop()` 에서 연결 대기를 실패로 끝냄 |

### 2.2 그대로 둔 것

| ID | 심각도 | 위치 | 내용 | 그대로 둔 이유 · 제안 |
|---|---|---|---|---|
| L-01 | 높음 | `PleosSignals.kt:27-33`, `:291` | 시동 ON·OFF 가 실제 시동과 다름. 3절 참고 | SDK 에 API 가 없음. 검수 뒤 운전석 탑승 신호로 바꿈(3절) |
| L-02 | 중간 | `PleosCabin.kt:94`, `core/model/Models.kt:15-20` | 앱의 네 색 ARGB(`#FF8E6CC0` 등)가 차·에뮬레이터의 프리셋 값과 같다는 보장이 없음. Virtual Console 은 Cyan · Blue · Purple · White 4종만 표시 | 프리셋의 실제 값을 알 수 없음. 4절 5~6번으로 값을 확인한 뒤, 다르면 어댑터에 "앱 색 → 차 프리셋 ARGB" 표를 두는 것을 제안 |
| L-03 | 중간 | `PleosCabin.kt:99-107` | 밝기 0 을 주면 스위치는 그대로 두고 최소 밝기만 설정. 원래 스위치가 꺼져 있던 차에서는 연출 뒤 조명이 켜진 채 남을 수 있음(`CabinState` 에 스위치 상태가 없음) | 계약에 스위치가 없고 Virtual Console 에도 스위치 카드가 없어 검증 불가. 제안: 밝기 0 = 스위치 OFF 로 약속하고 어댑터가 OFF 를 보냄 |
| L-04 | 중간 | `PleosVoice.kt:97-129`, `:198` | `speak()` 는 `onDone` 을 '재생이 끝남'으로 믿고 기다림. 종료 이벤트가 전혀 오지 않으면 문장마다 10초 + 글자당 0.35초를 기다린 뒤 실패로 돌려줌 | 이벤트 뜻은 서비스 쪽 동작이라 AAR 로 알 수 없음(4절 10번). 이벤트가 안 오는 환경이 확인되면 "시작 이벤트 없음 → 빠른 실패"를 넣을 것 |
| L-05 | 낮음 | `PleosSupport.kt:37-60` | `awaitSdk` 의 시간 초과는 '콜백이 안 옴'만 막음. SDK 호출 자체(메인 스레드의 바인더 호출)가 멈추면 막을 수 없음 | SDK 내부 큐가 스레드 안전하지 않아(LinkedList) 호출을 다른 스레드로 옮기지 않음 |
| L-06 | 낮음 | `PleosCabin.kt` 제어 전반 | 1절 3번: 서비스 재연결 중에 보낸 set 은 시간 초과로 실패를 돌려준 뒤에도 연결되면 실행될 수 있음 | SDK 동작. 순서는 지켜지므로 마지막 요청이 최종 상태가 됨 |
| L-07 | 낮음 | `PleosSignals.kt:215-220`, `PleosCabin.kt:309`, `:483-487` | 운전석 = `ROW_1_LEFT`(좌핸들 가정). 2열 가운데·3열은 `SeatOccupancy` 에 자리가 없어 세지 않음. 운전석 착좌는 항상 '탑승'으로 둠 | 한국 출시 차량 기준으로 맞음. 우핸들 지역은 대상 아님 |
| L-08 | 낮음 | `PleosPlatform.kt:56` | `PleosPlatform.release()` 를 부르는 곳이 없음 → Vehicle · TTS · STT · FusedLocation 을 프로세스가 끝날 때까지 해제하지 않음 | 프로세스 수명 동안 쓰는 연결이라 동작에는 문제없음. 검수 뒤 `AppContainer.shutdown()`(`NaitaeApp.onTerminate`)이 부르게 함 |
| L-09 | 낮음 | `vehicle/VehicleGateways.kt:176-178` | `SdkLog.add` 가 읽고-쓰기 두 단계라, 바인더 스레드의 이벤트 로그와 메인의 로그가 겹치면 한 줄이 사라질 수 있음(앱이 죽지는 않음) | 계약 파일. 제안: `_lines.update { (it + line).takeLast(MAX) }` |
| L-10 | 낮음 | `PleosNavi.kt:143-182`, `:328` | `requestRoute` 는 연결 6초 + 시작 이벤트 5초까지 기다림. 코디네이터가 이 호출을 명령 줄(commandLock) 안에서 불러, 그동안 조명 되돌리기 같은 뒤 명령이 밀림 | 호출 방식은 app 담당. 내비가 시작 이벤트를 주지 않는 환경이면 `ROUTE_WAIT_MS` 를 줄일 것 |
| L-11 | 낮음 | `PleosNavi.kt:153-155` | 즐겨찾기가 아닌 장소는 `poiId` · `address` · `poiSubId` 를 빈 문자열로 보냄 | 허용 여부는 문서에도 없음(4절 12번) |
| L-12 | 정보 | `PleosSupport.kt:109-140`, `:157` | 연결 확인은 20번(1~2분)까지만 함. 그 뒤에는 실내 제어 호출이나 `start()` 가 다시 시작시킴 | 무한 반복하면 SDK 큐에 호출이 계속 쌓이므로 제한 유지 |
| L-13 | 정보 | `PleosSignals.kt:134-148` | VIN 을 못 읽는 환경(에뮬레이터에 VIN 카드 없음)에서는 시동 흐름마다 다시 묻고 최대 4초 기다림 | 값이 생기면 바로 반영하려는 의도. 지연이 보이면 실패도 잠시 기억하도록 변경 |
| L-14 | 정보 | `PleosPlatform.kt:26` | `runtimePermissions` 를 쓰는 곳이 없음. `MainActivity` 가 선언된 권한 중 dangerous 를 스스로 골라 요청하므로 `FUSED_LOCATION` 은 요청됨 | 동작에 문제없음 |

## 3. 시동(ignitionOn): 운전석 탑승 신호로 확정

검수 때의 정의("앱 프로세스가 차량 연결을 시작한 때 = 시동 ON", 프로세스당 한 번)로는 다시 시동을 거는 장면이 재현되지 않았다. 3.3절의 대안 가운데 **D 안(운전석 착좌)** 을 골라 구현했다.

### 3.1 지금 ignitionOn 이 뜻하는 것

- Vehicle SDK 2.3.2 공개 API 에는 시동·전원 상태를 읽는 함수가 없음(1절 12번).
- 어댑터의 정의: **차량 서비스에 연결된 채 운전석(`ROW_1_LEFT`)에 앉아 있음 = ON.** 판단은 `vehicle/pleos/DriverSeatIgnition.kt`(SDK 를 모르는 순수 로직, 단위 테스트 10개).

| 상황 | ignitionOn | 코디네이터에서 일어나는 일 |
|---|---|---|
| 연결되어 첫 값을 읽은 때 운전석 착석 | false → true(바로) | 시동 흐름 1회 |
| 운전석 Empty → Occupied | 1.5초 뒤 true | 시동 흐름 1회: 이어받기 → "오늘을 남길까요?" → 편지 → 하루 첫 브리핑 → 저녁 메모 → 길 안내 질문 |
| 기어 P 에서 Occupied → Empty | 1.5초 뒤 false | 시동 OFF 처리(낭독·연출 중지, 조명 복원, 시동 꺼짐 화면) |
| 1.5초 안에 되돌아온 착좌 신호 | 그대로 | 아무 일도 없음(흐름이 다시 시작되지 않음) |
| 기어가 P 가 아닐 때의 Empty | 그대로 | 아무 일도 없음. P 가 되었을 때도 비어 있으면 그때부터 1.5초 뒤 false |
| 운전석 착좌 값을 받은 적이 없음(센서 없음 · capability 에 없음 · 읽기 실패) | 첫 값을 읽은 때부터 true | 검수 때의 정의와 같음(프로세스당 1회) |
| `stop()`(화면도 지켜보는 서비스도 연결을 놓음) | false | 시동 OFF 처리 |

- 좌석 수(`seats`)에서는 운전석을 여전히 탑승으로 둠(L-07). 운전석 센서 값은 탑승 신호로만 씀.
- 로그: `차량 값 수신 · 운전석 착석 → 탑승(시동 ON)으로 봄` / `… 운전석 비어 있음 → 탑승을 기다림` / `… 운전석 착좌 신호 없음 → 시동 ON 으로 봄`, 이후 `운전석 착석 → 탑승(시동 ON)으로 봄`, `운전석 비움(기어 P) → 내림(시동 OFF)으로 봄`.

### 3.2 이 정의로 동작하는 범위

- 동작함: 하루 첫 탑승의 브리핑, 저녁에 다시 탔을 때의 메모 다시 알림, 도착해 시간을 보낸 뒤 다시 탔을 때의 "오늘을 남길까요?", 정한 날 탑승에 열리는 편지, 다음 날 아침의 브리핑(헤드유닛이 대기했다 깨어나는 차 포함. 프로세스가 살아 있으면 착좌 리스너가 그대로 받음).
- 에뮬레이터: Virtual Console 의 Seat Occupancy 카드에서 운전석을 Occupied / Empty 로 바꾸는 것이 탑승 / 내림. 기본값이 Empty 면 앱은 시동 꺼짐 화면에서 기다림.
- 프로세스가 죽었다 다시 뜨면 새 탑승으로 봄. 이동 상태(기록 초안, 세워 둔 곳, 이미 물은 길 안내, 미뤄 둔 편지)는 `files/naitae-session.json` 에서 이어받으므로 "오늘을 남길까요?"가 이어짐.
- 남는 한계: 운전석 착좌를 알려 주지 않는 차에서는 검수 때와 같이 프로세스당 한 번. 부팅 때 스스로 뜨지 않으므로(노트 6.4절) 프로세스가 없으면 사용자가 앱을 열어야 그날의 흐름이 시작됨. 시동을 건 채 운전석을 비웠다 앉는 것(잠깐 내렸다 탐)도 새 탑승으로 봄.

### 3.3 검토한 대안과 결정

| 안 | 내용 | 결정 |
|---|---|---|
| A | 대기 후 깨어남 감지(`elapsedRealtime` 과 코루틴 시계의 차이) | 채택하지 않음. 에뮬레이터로 검증할 수 없음 |
| B | 날짜가 바뀌었고 기어가 P 면 시동 흐름을 다시 시작 | 채택하지 않음. 하루 첫 브리핑만 해결됨 |
| C | 도착 뒤 P 로 오래 머문 다음 화면이 다시 앞에 오면 '다시 시동' | 채택하지 않음. 화면 상태에 기대는 추정 |
| **D** | **운전석 착좌를 탑승 신호로 사용: Empty → Occupied = ON, 기어 P 에서 Empty = OFF** | **채택.** Virtual Console 의 Seat Occupancy 카드로 시연할 수 있고, 착좌를 알 수 없는 차는 예전 동작으로 돌아감 |

`IgnitionState` enum 이 이미 SDK 에 들어 있어 다음 SDK 판에서 시동 API 가 열릴 가능성이 있음. 열리면 `PleosSignals` 의 `boarding`(BoardingWatch) 입력만 바꾸면 됨.

## 4. 에뮬레이터·실차에서만 확인되는 가정 — Virtual Console 점검표

준비: AVD Cold boot → `adb forward tcp:8088 tcp:8088` → 브라우저 `http://127.0.0.1:8088/`. 앱의 SDK 로그는 `SdkLog`(화면의 로그 표시)로 확인. "기대 로그"는 어댑터가 실제로 남기는 문장 그대로임.

| # | 조작(카드) | 앱 SDK 로그에 보여야 할 줄 | 확인하는 가정 | 다르게 나오면 |
|---|---|---|---|---|
| 1 | 앱 실행 | `Vehicle.initialize()` → `Vehicle 서비스 연결됨` → `차량 신호 수신 시작` → `Gear.registerGearSelection(listener)` → `Gear.getCurrentGearSelectionState() → GEAR_PARK` → `Odometer.getCurrentDistanceDriven() → …km` → `Seat.checkSeatOccupancyCapabilities() → 좌석 영역 N곳` → `CarInfo.getVINInfo() → …` → `차량 값 수신 · 운전석 착석 → 탑승(시동 ON)으로 봄`(운전석이 Empty 면 `… 운전석 비어 있음 → 탑승을 기다림`) → 탑승이면 `나이테 · 시동 ON` | Pleos 판별(F-07), 연결 확인, 탑승 신호(3절) | `Pleos Vehicle 서비스 없음 … → 시뮬레이터로 실행` 이면 서비스 패키지·컴포넌트 이름 확인. `… need Permission` 이면 해당 권한 미부여 |
| 2 | Motion · Gear Selection: P → D → P | `Gear.onSelected → GEAR_DRIVE`, `Gear.onSelected → GEAR_PARK`. D 에서 화면 입력이 잠김 | 기어 구독, 주행 중 잠금 | 이벤트가 없으면 `Gear 리스너 오류 …` 확인 |
| 3 | Motion · Odometer: 값을 10 올림 | `Odometer.onDistanceDrivenUpdated → 12,850km` 형태 | 단위가 km | 값이 1000배면 단위가 m |
| 4 | Convenience · Seat Occupancy: 동승석 Occupied, 이어서 운전석 Empty | `Seat.onSeatOccupancyUpdated(동승석) → 착석`, `Seat.onSeatOccupancyUpdated(운전석) → 비어 있음` | `ROW_1_LEFT` = 운전석, `ROW_1_RIGHT` = 동승석(L-07) | 운전석 카드에 `(동승석)` 이 찍히면 좌우를 바꿔야 함 |
| 5 | Convenience · Ambient Brightness 40%, Ambient Color 를 4색 차례로 | `Light.onBrightnessUpdated → 40%`, `Light.onBrightnessColorUpdated → #AARRGGBB (색 이름)` 4줄 | 밝기 범위 환산, **프리셋의 실제 ARGB 값**(L-02), F-06 | 찍힌 ARGB 4개를 적어 둘 것. 색 이름이 카드와 다르면 프리셋 표가 필요 |
| 6 | 앱에서 기록 꾸미기 → 보라 선택 | `Light.setAmbientLightBrightnessColor(#FF8E6CC0 보라) → 적용`, `Light.setAmbientLightBrightness(90%) → 적용`, 이어서 5번의 리스너 줄. Virtual Console 카드가 Purple · 90% 로 바뀜 | 임의 ARGB 를 받아 주는지, `CONTROL_CAR_LIGHTS` 부여 | `→ 실패 · ERROR_…` 또는 카드가 안 바뀌면 5번에서 적은 프리셋 값으로 보내도록 수정 |
| 7 | 5번으로 Blue · 40% 를 만든 뒤 6번 실행 → 꾸미기 닫기 | `Light.setAmbientLightBrightnessColor(… 파랑) → 적용`, `Light.setAmbientLightBrightness(40%) → 적용`. 카드가 Blue · 40% 로 돌아감 | 원래 조명 되돌리기(F-06) | 앱을 켰을 때의 값으로 돌아가면 리스너 등록 실패 로그 확인 |
| 8 | 미래 편지 열기(오늘 열 편지가 있을 때) | `Seat.checkSeatTemperatureCapabilities() → READ_WRITE · 영역 N곳`, `Seat.setHvacSeatTemperature(운전석, 3단) → 적용`, `SteeringWheel.setHvacSteeringWheelHeat(2단) → 적용`, `HVAC.setHvacTemperature(24.0℃) → 적용`, `HVAC.setHvacFanSpeed(1단) → 적용`. 카드: Seat Temperature 운전석 열선 3단계, Steering Wheel Heat Medium, Target Temperature 24.0, Fan Speed 1 | 시트 열선 값이 양수 = 열선, 단계 환산, 온도 단위 | 운전석이 '통풍'으로 바뀌면 부호가 반대. `이 차에서 지원하지 않음` 이면 capability 가 READ 뿐 |
| 9 | Climate · HVAC Power Off 로 둔 뒤 8번 | `HVAC.getHvacPowerState() → 꺼짐`, `HVAC.setHvacPower(ON) → 켜짐` | 공조 전원 선행 | 온도·풍량이 반영되지 않으면 전원 영역 확인 |
| 10 | 브리핑 듣기 → 중간에 "그만" | `TextToSpeech(ON_DEVICE).initialize() → 준비됨`, `TextToSpeech.setLocale(ko-KR)`, 문장마다 `TextToSpeech.speak("…")` → `TextToSpeech.onStart` → `TextToSpeech.onDone`. **다음 speak 줄은 앞 문장의 소리가 끝난 뒤에** 나와야 함. 그만: `TextToSpeech.stop()` → `TextToSpeech.onStop`, 소리가 바로 멈춤 | `onDone` = 재생 끝(L-04), 한국어 화자 | 소리가 겹치면 `onDone` 이 합성 완료 시점 → 완료 판단을 바꿔야 함. `onStart` · `onDone` 이 없으면 문장마다 10초 넘게 멈춤 → 빠른 실패 추가 |
| 11 | 에뮬레이터 Extended Controls · Microphone 켜고 "메모할 것 있으세요?"에 말하기 / 말하지 않기 | `SpeechToText(ON_DEVICE).initialize() → 준비됨`, `SpeechToText.request() · ON_DEVICE · ko-KR`, `SpeechToText.onReady`, `SpeechToText.onStartedRecognition`, `SpeechToText.onEndedRecognition`, `SpeechToText.onUpdated(완료) → "…"`, `음성 입력 결과 → "…"`, `SpeechToText.stop()`. 말이 없으면 8초쯤 뒤 `음성 입력 결과 → 말 없음` | **종료 이벤트와 완료 결과의 순서**(F-04), 마이크 권한 불필요 | 두 줄의 순서를 적어 둘 것. `onError → MIC_ERROR` 면 마이크 설정, `PERMISSION_DENIED` 면 `STT_SERVICE` |
| 12 | "○○로 안내할까요?" → 안내받기 | `NaviHelper.initialize(onConnected, onFailed)`, `내비게이션 서비스 연결됨`, `NaviHelper.addListener(listener)`, `NaviHelper.requestRoute(장소 · 위도 …, 경도 …)`, `NaviHelper.onRouteStarted → … · …km`. 내비 지도의 목적지가 그 장소 | 좌표 순서, 빈 `poiId` 허용(L-11), **`onRouteStarted` 와 `onRoutingCompleted` 가 언제 오는지**(F-03) | 15초 뒤 `내비게이션 서비스 연결 실패` 면 에뮬레이터에 내비 없음. `onError → CAUSE_SEARCH_ROUTE` 면 POI 정보 필요 |
| 13 | (실차 또는 내비 모의 주행) 목적지 도착 | `NaviHelper.onDestinationArrived → 이름 · 12.3km · 25분` | 거리 m, 시간 초 | 값이 터무니없으면 단위 재확인. Virtual Console 로는 재현 불가(노트 7.4절) |
| 14 | 장소 메모를 만든 상태로 앱 실행 → 권한 창에서 허용 | 권한 창에 `FUSED_LOCATION` 이 뜸. `FusedLocation.isSupported() → 지원`, `FusedLocation.initialize()`, `FusedLocation.addProximityAlert(장소, 반경 300m) → 등록됨`. 위치를 그 장소로 옮기면 `FusedLocation.onTransition(장소) → 가까워짐` | dangerous 권한 요청 경로, 등록 응답, 이미 반경 안일 때의 첫 전이(F-05) | 거부하면 `위치 권한(…)이 아직 허용되지 않음`. 에뮬레이터 위치가 FusedLocation 에 반영되는지는 미확인 |
| 15 | Seat Occupancy: 기어 P 에서 운전석 Empty → 2초 뒤 Occupied | `Seat.onSeatOccupancyUpdated(운전석) → 비어 있음` → 1.5초 뒤 `운전석 비움(기어 P) → 내림(시동 OFF)으로 봄`, `나이테 · 시동 OFF` → `Seat.onSeatOccupancyUpdated(운전석) → 착석` → 1.5초 뒤 `운전석 착석 → 탑승(시동 ON)으로 봄`, `나이테 · 시동 ON` | 운전석 착좌 값과 리스너가 에뮬레이터에서 오는지(3절) | `차량 값 수신 · 운전석 착좌 신호 없음 → 시동 ON 으로 봄` 이면 운전석 영역이 capability 에 없거나 읽기 실패 → 예전 동작(프로세스당 한 번) |
| 16 | 첫 실행 뒤 운전석 Occupied | `나이테 · '평일 · 출근길' 아침까지의 데이터로 바꿈` → `나이테 · 하루 첫 시동 → 브리핑 5줄`. 이어받기 로그(`새 차(VIN 다름)`)는 나오지 않음 | 가상 데이터의 첫 차를 차가 알려 준 VIN 으로 등록(시연 패널의 날짜를 고를 때마다) | 이어받기 화면이 뜨면 날짜를 고른 뒤에 VIN 이 처음 읽힌 것 → 같은 날짜 버튼을 다시 누름 |

## 5. 매니페스트

- 결론: **`docs/PLEOS_MANIFEST_SNIPPET.xml` 과 `app/src/main/AndroidManifest.xml` 모두 변경 필요 없음.** 스니펫은 고치지 않았음.

| 어댑터가 부르는 API | 실행 중 검사되는 권한(구현 클래스 기준) | 스니펫 |
|---|---|---|
| `Gear.*`, `Odometer.*` | `CAR_DRIVE` | 있음 |
| `CarInfo.getVINInfo` | `CAR_INFO` | 있음 |
| `Seat.checkSeatOccupancy… / getCurrentSeatOccupancy / registerSeatOccupancy / checkSeatTemperature… / getHvacSeatTemperature` | `CAR_SEATS` | 있음 |
| `Seat.setHvacSeatTemperature` | `CONTROL_CAR_SEATS` | 있음 |
| `Light.checkAmbientLight… / getAmbientLight… / registerAmbientLight…Listener`(F-06 에서 추가) | `CAR_LIGHTS` | 있음 |
| `Light.setAmbientLightSwitch / BrightnessColor / Brightness` | `CONTROL_CAR_LIGHTS` | 있음 |
| `HVAC.check… / get…`, `SteeringWheel.check… / get…` | `CAR_CLIMATE` | 있음 |
| `HVAC.setHvacPower / Temperature / FanSpeed`, `SteeringWheel.setHvacSteeringWheelHeat` | `CONTROL_CAR_CLIMATE` | 있음 |
| `NaviHelper.getBookmarkInfo / getDestinationInfo` | `NAVI_ROUTE` | 있음 |
| `NaviHelper.requestRoute` | `NAVI_ROUTE_SEARCH` | 있음 |
| `TextToSpeech` 전체 | `TTS_SERVICE` | 있음 |
| `SpeechToText` 전체 | `STT_SERVICE`(`RECORD_AUDIO` 는 검사하지 않음) | 있음 |
| `FusedLocation.addProximityAlert / removeProximityAlert` | `FUSED_LOCATION`(dangerous, `MainActivity` 가 실행 중 요청) | 있음 |

- F-07 의 컴포넌트 조회는 이미 선언된 `<queries><package android:name="ai.pleos.playground.service.vehicle" />` 로 보임. 추가 선언 없음.
- `<queries>`(내비 · 음성 · 위치), 바인딩 권한, `INTERNET` 은 AAR 이 병합함. `android.hardware.type.automotive` 를 `required="false"` 로 덮는 것도 이미 되어 있음.
- 참고(노트 0절 8번): Vehicle 권한은 Pleos App Market 의 사전 승인 대상. 선언한 `CAR_*` · `CONTROL_CAR_*` 8개는 승인 신청 목록에 넣어야 함.

## 6. 다른 담당이 알아야 할 점

### 6.1 컴파일 위험 지점(빌드로 확인할 곳)

| 위치 | 내용 | 근거 |
|---|---|---|
| `PleosVoice.kt:23`, `:299-304` | `kotlinx.coroutines.selects.select` 와 `Deferred.onAwait` 를 새로 씀 | coroutines 1.8.1 jar 에서 둘 다 실험 API 표시가 없음을 확인 |
| `PleosCabin.kt:10-11`, `:432-465` | `AmbientLightBrightnessListener.onBrightnessUpdated(Int?)`, `AmbientLightBrightnessColorListener.onBrightnessColorUpdated(Int?)` 구현 | AAR 에서 인자가 `@Nullable java.lang.Integer` 임을 확인(매개변수 이름이 달라 경고는 날 수 있음) |
| `PleosPlatform.kt:4`, `:118-123` | `android.content.ComponentName` 임포트 추가 | — |
| `PleosSignals.kt:161-178` | `readGear()` 추가(사용 중단 예고 API 호출에 `@Suppress("DEPRECATION")`) | `PleosSupport.kt` 의 같은 호출과 같은 형태 |

### 6.2 문서에 반영할 내용(내가 고칠 수 없는 파일)

- `docs/SDK_USAGE.md` 조명 표, `README.md` 55행: 쓰는 API 에 `Light.registerAmbientLightBrightnessListener` · `registerAmbientLightBrightnessColorListener` 추가(F-06). "연결 직후 현재 조명을 읽어 둔다"는 "읽고, 이후 변화를 리스너로 따라간다"로.
- `docs/SDK_USAGE.md` 82행: `onRoutingCompleted` 는 안내 시작 판단에 쓰지 않음(F-03).
- 계약 변경 제안: L-03(`setAmbient` 밝기 0 = 끔), L-09(`SdkLog.add` 원자화), 3.3절의 시동 대안.

### 6.3 이번 검수에서 건드리지 않은 변경

- 작업 중 `app/build.gradle.kts`, `vehicle/sim/SimVehicle.kt` 등이 다른 담당에 의해 바뀌고 있었음. 이 검수의 변경은 `vehicle/pleos/` 의 7개 파일과 이 문서뿐임.
