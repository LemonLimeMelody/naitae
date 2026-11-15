# Pleos Connect SDK 활용

「나이테」가 Pleos Connect SDK 를 어디서 어떻게 부르는지 정리한 문서다. 여기 적은 클래스와 메서드는 모두 `app/src/main/java/com/naitae/connect/vehicle/pleos/` 의 코드에 있는 것이다. Pleos 에뮬레이터와 실차에서의 실행 확인은 하지 않았다([README 의 검증 상태](../README.md#검증-상태)).

## 1. 구조

앱의 나머지 부분은 `vehicle/VehicleGateways.kt` 의 인터페이스만 본다. 구현은 둘이다.

| 인터페이스 | 하는 일 | Pleos 구현 | 시뮬레이터 구현 |
|---|---|---|---|
| `VehicleSignals` | 시동, 기어, 누적 주행거리, 좌석 착석, VIN 읽기 | `PleosSignals` | `SimVehicle.signals` |
| `CabinControl` | 실내 조명, 시트 열선, 핸들 열선, 공조 설정 | `PleosCabin` | `SimCabin` |
| `NaviGateway` | 길 안내 요청, 도착 이벤트, 즐겨찾기 | `PleosNavi` | `SimNavi` |
| `VoiceOutput` · `VoiceInput` | 낭독 · 음성 입력 | `PleosVoiceOutput` · `PleosVoiceInput` | `SimVoiceOutput` · `SimVoiceInput` |
| `ProximityGateway` | 장소 근접 알림 | `PleosProximity` | `SimProximity` |

### 실행 환경 판단

`PleosPlatform.createOrNull()` 이 `PackageManager.resolveService()` 로 Pleos Vehicle 서비스(액션 `ai.pleos.playground.vehicle.VEHICLE_START`, 패키지 `ai.pleos.playground.service.vehicle`)를 찾는다. 있으면 Pleos 어댑터를 만들고, 없거나 SDK 생성·초기화에서 예외가 나면 `null` 을 돌려주어 `AppContainer` 가 시뮬레이터로 넘어간다. 서비스가 없는 기기에서는 SDK 객체를 하나도 만들지 않는다.

### 공통 실패 처리 (`PleosSupport.kt`)

| 도구 | 동작 |
|---|---|
| `awaitSdk(label, timeoutMs = 4000)` | 콜백형 SDK 호출을 `Result` 로 바꾼다. 예외, 실패 콜백, 4초 무응답을 모두 `Result.failure` 로 돌려주고 던지지 않는다. 콜백이 두 번 와도 한 번만 처리한다 |
| `safeSdk(label) { }` | 리스너 등록·해제, `release()` 같은 호출을 감싸 예외를 로그로만 남긴다 |
| `Throwable.brief()` | 로그용 짧은 오류 설명. Vehicle SDK 2.3.2 의 예외 클래스는 난독화되어 있어 타입 대신 메시지(`ERROR_…` 코드 이름)를 쓴다 |
| `SdkLog.api / event / sys` | 호출·이벤트·상태를 한 줄씩 남긴다. 시뮬레이터 모드의 가상 콘솔 "SDK 호출 로그"에 표시된다 |

한 흐름이 실패해도 코디네이터(`NaitaeCoordinator.guarded`)가 예외를 잡아 앱은 계속 실행된다.

## 2. Vehicle SDK (`ai.pleos.playground:Vehicle:2.3.2`)

### 2.1 초기화와 연결 확인

| 호출 | 파일 | 설명 |
|---|---|---|
| `Vehicle(context)`, `initialize()` | `PleosPlatform.kt` | 앱 시작 때 한 번 만든다. 신호 읽기와 실내 제어가 같은 인스턴스를 쓴다 |
| `getGear().getCurrentGearSelectionState()` | `PleosSupport.kt` (`PleosVehicleSession.connect`) | `initialize()` 는 연결 결과를 알려 주지 않으므로 기어 값을 읽어 보고 답이 오면 연결된 것으로 본다. 실패하면 0.4초에서 3초까지 간격을 늘리며 20번까지 다시 읽는다 |
| `release()` | `PleosSupport.kt` (`PleosVehicleSession.release`) | 연결을 닫는다 |

제어 호출은 연결을 최대 6초 기다리고, 그 안에 연결되지 않으면 실패를 돌려준다.

### 2.2 차량 신호 읽기 (`PleosSignals.kt`)

| API | 쓰는 기능 | 권한 | 실패 처리 |
|---|---|---|---|
| `Gear.registerGearSelection(GearSelectionListener)` → `onSelected(VehicleGear)`, `unregisterGearSelection` | P 여부 판단. P 에서만 화면 흐름을 시작하고, P 가 아니면 낭독·연출을 멈추고 터치를 잠근다. `GEAR_DRIVE` 와 수동 단수는 모두 주행으로 본다 | `CAR_DRIVE` | 리스너 `onFailed` 는 로그만 남긴다. 값이 없으면 `UNKNOWN` 으로 두고 화면 흐름을 시작하지 않는다 |
| `Odometer.registerDistanceDriven(DistanceDrivenListener)` → `onDistanceDrivenUpdated(Float?)`, `getCurrentDistanceDriven`, `unregisterDistanceDriven` | 이번 이동 거리(도착 이벤트에 거리가 없을 때 시동 뒤 늘어난 값), 이어받기 화면의 새 차 주행거리 | `CAR_DRIVE` | 읽지 못하면 `null` 로 두고 진행한다 |
| `Seat.checkSeatOccupancyCapabilities`, `registerSeatOccupancy(SeatArea, SeatOccupancyListener)` → `onSeatOccupancyUpdated(Boolean?)`, `getCurrentSeatOccupancy`, `unregisterSeatOccupancy` | 기록의 좌석 수(함께한 날 여부). 동승자가 있으면 개인 메모는 읽지 않고, 새 차 첫 시동의 편지는 바로 열지 않고 먼저 묻는다 | `CAR_SEATS` | 지원 영역 목록을 받으면 그 안에서 `ROW_1_LEFT` · `ROW_1_RIGHT` · `ROW_2_LEFT` · `ROW_2_RIGHT` 를 찾고, 못 받으면 좌석 하나짜리 영역으로 시도한다. 운전석은 센서 값과 무관하게 탑승으로 둔다. 해제는 등록한 영역·리스너 그대로 한다 |
| `CarInfo.getVINInfo` | 계정에 등록된 차와 VIN 이 다르면 이어받기 | `CAR_INFO` | 못 읽으면 `null` 을 돌려주고 이어받기 판단을 건너뛴다. 읽은 값은 캐시한다 |

시동 상태는 Vehicle SDK 공개 API 에 없다. 어댑터는 SDK 가 연결되어 차량 값이 처음 도착한 때를 시동 ON 으로, `stop()` 이 불린 때를 OFF 로 본다. 앱이 켜진 채로 시동만 껐다 켜는 경우는 구분하지 못한다. 이 부분이 10절의 시동 이벤트 API 제안으로 이어진다.

### 2.3 실내 제어 (`PleosCabin.kt`)

모든 설정은 먼저 `check…Capabilities` 로 접근 유형(`AccessType.WRITE` · `READ_WRITE`)과 영역·범위를 받아 캐시하고 그 안에서만 부른다. 기능 확인이 응답하지 않으면 캐시하지 않고 다음 호출에서 다시 묻는다.

| API | 쓰는 기능 | 권한 | 값 처리와 실패 처리 |
|---|---|---|---|
| `Light.checkAmbientLightBrightnessColorCapabilities`, `checkAmbientLightBrightnessCapabilities`, `checkAmbientLightSwitchCapabilities` | 실내 조명 지원 영역, 밝기 범위, 스위치 지원 확인. 꾸미기 화면이 고를 수 있는 색 목록도 여기서 정한다 | `CAR_LIGHTS` | 색·밝기 영역이 모두 없으면 "지원하지 않음"으로 실패를 돌려준다 |
| `Light.setAmbientLightSwitch(area, LightSwitch.ON)` | 밝기를 0보다 크게 줄 때 스위치를 먼저 켠다(스위치를 지원하는 영역만) | `CAR_LIGHTS`, `CONTROL_CAR_LIGHTS` | 실패는 로그만 남기고 색·밝기 설정을 이어 간다 |
| `Light.setAmbientLightBrightnessColor(area, argb)` | 함께 꾸미기에서 고른 색, 다시 보기의 그날 색, 편지의 조명 색 | `CAR_LIGHTS`, `CONTROL_CAR_LIGHTS` | 32비트 ARGB 로 보낸다(보라 `#FF8E6CC0`, 파랑 `#FF3E7BD6`, 청록 `#FF2DB7C4`, 흰색 `#FFF4EBDD`). 같은 색은 10초 안에 다시 보내지 않는다 |
| `Light.setAmbientLightBrightness(area, value)` | 꾸미기 90%, 다시 보기 95%, 편지는 0에서 목표 밝기까지 10단계로 올림 | `CAR_LIGHTS`, `CONTROL_CAR_LIGHTS` | 0~100% 를 차가 알려 준 `AreaBounds` 로 환산한다 |
| `Light.getAmbientLightBrightness`, `getAmbientLightBrightnessColor` | 연결 직후 현재 조명을 읽어 둔다. 연출 뒤 원래 조명으로 되돌리는 기준이다. 색은 앱의 네 색 가운데 가장 가까운 색으로 기억한다 | `CAR_LIGHTS` | 색을 못 읽었으면 쓰던 색 그대로 밝기만 되돌리고, 밝기를 못 읽었으면 0 으로 둔다 |
| `Seat.checkSeatTemperatureCapabilities`, `setHvacSeatTemperature(area, level)`, `getHvacSeatTemperature` | 편지 연출의 운전석 시트 열선 | `CAR_SEATS`, `CONTROL_CAR_SEATS` | 앱의 0~3단을 차의 최대 단계에 비례해 맞춘다. 운전석 영역이 없거나 쓰기 불가면 실패를 돌려준다 |
| `SteeringWheel.checkHvacSteeringWheelHeatCapabilities`, `setHvacSteeringWheelHeat(level)`, `getHvacSteeringWheelHeatState` | 편지 연출의 핸들 열선 | `CAR_CLIMATE`, `CONTROL_CAR_CLIMATE` | 켤 때는 중간 단계(3단 차면 2단), 끌 때는 0 |
| `HVAC.checkHvacTemperatureCapabilities`, `checkHvacFanSpeedCapabilities`, `checkHvacPowerCapabilities`, `getHvacPowerState`, `setHvacPower`, `setHvacTemperature(area, value, TemperatureUnit.CELSIUS)`, `setHvacFanSpeed`, `getHvacTemperature`, `getHvacFanSpeed` | 편지 연출의 실내 온도와 풍량 | `CAR_CLIMATE`, `CONTROL_CAR_CLIMATE` | 공조 전원이 꺼져 있으면 먼저 켠다. 온도는 0.5℃ 단위로 맞추고 차가 알려 준 범위 안으로 자른다. 풍량도 범위 안으로 자른다 |

편지 연출(`domain/Letters.kt` 의 `LetterPerformer`)은 개별 설정이 실패해도 다음 단계로 넘어가 낭독까지 마친다. 끝나면 실내 조명만 연출 전 값으로 되돌리고 열선과 공조는 그대로 둔다.

매니페스트에는 `CAR_LIGHTS` 와 함께 `CONTROL_CAR_LIGHTS` 도 선언했다. 조명 설정에 필요한 권한이 문서의 API Reference 에는 `CAR_LIGHTS` 로, SDK 구현의 실행 중 검사에는 `CONTROL_CAR_LIGHTS` 로 되어 있기 때문이다.

## 3. NaviHelper SDK (`ai.pleos.playground:NaviHelper:3.0.5`, `PleosNavi.kt`)

| API | 쓰는 기능 | 권한 | 실패 처리 |
|---|---|---|---|
| `NaviHelper(context)`, `initialize(onConnected, onFailed)`, `addListener(NaviHelperEventListener)`, `removeListener`, `release` | 연결과 이벤트 수신. 리스너는 `onConnected` 뒤에 등록한다 | - | 연결에 실패하면 인스턴스를 비워 두고 다음 요청에서 다시 연결한다. 6초 안에 연결되지 않으면 요청은 실패를 돌려준다 |
| `requestRoute(RouteInfo(latitude, longitude, poiName, poiId, address, poiSubId))` | "○○로 안내할까요?"에 사용자가 "안내해 줘"를 눌렀을 때만 부른다. 자동으로 길 안내를 시작하지 않는다 | `NAVI_ROUTE_SEARCH` | 위도·경도는 이름을 붙여 넘긴다. `onRouteStarted` · `onRoutingCompleted` · `onError` 를 5초 기다리고, 아무 이벤트가 없으면 요청이 전달된 것으로 본다. `onError(NaviErrorCode)` 면 실패를 돌려주고 코디네이터가 로그를 남긴다 |
| `getDestinationInfo()` → `onDestinationInfo(DestinationInfo)` | 도착 이벤트에는 좌표가 없으므로 경로가 시작되거나 목적지가 바뀔 때 목적지 좌표를 받아 둔다 | `NAVI_ROUTE` | 받지 못하면 앱이 요청한 장소의 좌표를 쓴다 |
| `onDestinationArrived(DestinationArrivedInfo)` | 도착 판단. 평소 도착은 화면 없이 지나가고, 특별한 날은 기록 초안을 준비하며, 예전 기록이 있는 곳이면 다시 보기를 연다. 거리(m)는 km 로, 시간(초)은 분으로 바꾼다 | `NAVI_ROUTE` 를 선언하고 수신(이벤트 수신 권한은 문서에 표기 없음) | 이름이 비어 있으면 받아 둔 목적지 이름을 쓴다 |
| `onRouteStarted`, `onRoutingCompleted`, `onRouteCancelled`, `onRouteEnded`, `onDestinationChanged` | 안내 시작·종료 상태 추적 | 위와 같음 | `onRouteEnded` 는 사용 중단 예고된 이벤트이며 함께 받는다 |
| `getBookmarkInfo()` → `onBookmarkInfo(List<BookmarkInfo>)` | 즐겨찾기를 주제의 장소로 고르는 용도. `NaviGateway.bookmarks()` 로 구현되어 있고, 현재 화면에는 이 함수를 부르는 곳이 없다 | `NAVI_ROUTE` | 3초 안에 답이 없으면 마지막으로 받은 목록을 쓴다 |

`RouteInfo` 의 `poiId` · `address` · `poiSubId` 는 같은 이름·위치의 즐겨찾기가 캐시에 있으면 그 값을, 없으면 빈 문자열을 넣는다.

## 4. TextToSpeech SDK (`ai.pleos.playground:TextToSpeech:3.3.1`, `PleosVoice.kt` 의 `PleosVoiceOutput`)

| API | 쓰는 기능 | 권한 | 실패 처리 |
|---|---|---|---|
| `TextToSpeech(context, Mode.ON_DEVICE)`, `initialize(OnInitializeListener)`, `setLocale(Locale.KOREA)`, `addEventListener(EventListener)` | 차 안 엔진과 한국어로 준비한다. `ON_DEVICE` 만 쓰므로 앱 등록(`registerApp`)을 하지 않는다. 앱 시작 때 미리 초기화한다 | `TTS_SERVICE` | 초기화가 실패하거나 5초 안에 끝나지 않으면 30초 뒤에 다시 시도한다 |
| `speak(text, isFinal = true)` → `onStart`, `onDone`, `onStop`, `onError(ErrorType)` | 브리핑, 메모 다시 알림, 길 안내 질문, "오늘을 남길까요?", 다시 보기의 한마디, 편지 낭독. 한 문장씩 끝날 때까지 기다린다 | `TTS_SERVICE` | 실패하면 코디네이터가 같은 문장을 화면 아래 자막으로 읽을 시간만큼 보여 준다. 종료 이벤트가 오지 않으면 문장 길이에 맞춘 시간 뒤에 멈춘다 |
| `stop()` | 출발(P 를 벗어남), 시동 끔, "그만", 화면 닫기 | `TTS_SERVICE` | - |
| `removeEventListener`, `release` | 연결 해제 | - | - |

화자는 지정하지 않고 플랫폼 기본 화자를 쓴다.

## 5. SpeechToText SDK (`ai.pleos.playground:SpeechToText:3.3.1`, `PleosVoice.kt` 의 `PleosVoiceInput`)

| API | 쓰는 기능 | 권한 | 실패 처리 |
|---|---|---|---|
| `SpeechToText(context, Mode.ON_DEVICE)`, `initialize(OnInitializeListener)`, `setLocale(Locale.KOREA)`, `addListener(ResultListener)` | 차 안 엔진과 한국어로 준비한다 | `STT_SERVICE` | 초기화가 실패하면 30초 뒤에 다시 시도하고, 그동안 듣기는 실패를 돌려준다 |
| `request()` → `onReady`, `onStartedRecognition`, `onUpdated(stt, completed)`, `onEndedRecognition`, `onError(ErrorType)` | 브리핑 끝의 "메모할 것 있으세요?"와 남긴 기록의 "한마디 남기기". 인식 중인 글을 화면에 실시간으로 보여 준다 | `STT_SERVICE` | 8초 동안 말이 없으면 "말 없음"으로 끝낸다. 말이 시작됐으면 4초 더 기다린다. `ErrorType.TIMEOUT` 은 "말 없음"으로 본다. 그 밖의 오류는 실패로 돌려주고 메모·한마디를 남기지 않은 채 흐름을 이어 간다 |
| `stop()` | 인식이 끝났거나 화면을 떠날 때 마이크를 닫는다 | `STT_SERVICE` | - |
| `removeListener`, `release` | 연결 해제 | - | - |

## 6. FusedLocation SDK (`ai.pleos.playground:FusedLocation:1.1.3`, `PleosProximity.kt`)

| API | 쓰는 기능 | 권한 | 실패 처리 |
|---|---|---|---|
| `FusedLocation(context)`, `isSupported()`, `initialize()`, `release()` | 위치 서비스 지원 확인과 연결 | `FUSED_LOCATION` | 지원하지 않으면 근접 알림을 등록하지 않는다 |
| `addProximityAlert(ProximityAlertRequest(latitude, longitude, radiusMeters, transitionTypes = {ENTER, EXIT}), ProximityAlertListener)` → `onRegistered(alertId)`, `onTransition(ProximityAlertEvent)`, `onError` | 장소를 붙인 메모(`MemoRemind.AtPlace`, 기본 반경 300m)를 그곳에 가까워지면 카드와 음성으로 알려 준다 | `FUSED_LOCATION` (dangerous) | 권한이 허용되지 않았으면 호출하지 않고 실패를 돌려주며, 권한을 받은 뒤 다시 등록한다. 등록 응답이 5초 안에 없으면 실패로 보고, 뒤늦게 등록된 알림은 바로 해제한다 |
| `removeProximityAlert(alertId)` | 메모를 알린 뒤, 또는 같은 장소의 알림을 다시 걸 때 해제 | `FUSED_LOCATION` | - |

근접 알림은 장소를 붙인 메모가 계정에 있을 때만 등록한다. 가상 데이터에는 장소를 붙인 메모가 없고, 차에서 말로 남기는 메모는 다음 시동 또는 퇴근길 시동 알림으로 저장되므로, 여섯 날의 시연에서는 이 SDK 호출이 일어나지 않는다. 그곳에 가까워졌을 때 알릴 메모를 고르는 로직(`MemoEngine.dueAtPlace`)에는 단위 테스트가 있다.

## 7. 권한 요약

| 권한 | 보호 수준 | 쓰는 API |
|---|---|---|
| `pleos.car.permission.CAR_DRIVE` | normal | Gear, Odometer |
| `pleos.car.permission.CAR_INFO` | normal | CarInfo |
| `pleos.car.permission.CAR_SEATS` | normal | Seat 착석 여부, 시트 열선 범위·현재 값 |
| `pleos.car.permission.CONTROL_CAR_SEATS` | normal | `Seat.setHvacSeatTemperature` |
| `pleos.car.permission.CAR_LIGHTS` | normal | Light 지원 확인·조회·설정 |
| `pleos.car.permission.CONTROL_CAR_LIGHTS` | normal | Light 설정 |
| `pleos.car.permission.CAR_CLIMATE` | normal | HVAC·SteeringWheel 지원 확인·조회 |
| `pleos.car.permission.CONTROL_CAR_CLIMATE` | normal | HVAC·SteeringWheel 설정 |
| `pleos.car.permission.NAVI_ROUTE` | normal | 목적지·즐겨찾기 조회, 이벤트 수신 |
| `pleos.car.permission.NAVI_ROUTE_SEARCH` | normal | `requestRoute` |
| `pleos.car.permission.TTS_SERVICE` | 문서에 표기 없음 | TextToSpeech |
| `pleos.car.permission.STT_SERVICE` | 문서에 표기 없음 | SpeechToText |
| `pleos.car.permission.FUSED_LOCATION` | dangerous | FusedLocation |

`FUSED_LOCATION` 은 `MainActivity` 가 첫 실행에 요청한다. SDK AAR 이 병합하는 바인딩 권한, `<queries>`, `INTERNET` 은 앱 매니페스트에 다시 쓰지 않았다. 근거는 [`PLEOS_NOTES.md`](PLEOS_NOTES.md) 1절과 [`PLEOS_MANIFEST_SNIPPET.xml`](PLEOS_MANIFEST_SNIPPET.xml) 에 있다.

## 8. 플랫폼 지침을 반영한 부분

| 지침 | 구현 |
|---|---|
| 주행 중 조작 제한 | 기어가 P 가 아니면 화면 본문 위에 터치를 받지 않는 덮개를 올리고(`NaitaeScreens.kt`), 코디네이터가 조작 요청을 무시한다(`whenParked`). 액티비티에 `distractionOptimized` 를 선언했다 |
| 오디오 포커스 | 녹음 재생(`AssetVoicePlayer`)은 `USAGE_ASSISTANT` 속성으로 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` 을 요청하고, 포커스를 잃으면 바로 멈추며, 끝나면 반납한다 |
| 포그라운드 서비스 | `VehicleWatchService` 는 `specialUse` 유형이며 Pleos 모드에서만 시작한다. 부팅 때 스스로 시작하지 않는다 |
| 로터리 조작 | 지원하지 않는다고 선언했다(`com.hmg.rotary.ROTARY_SUPPORTED=false`). 모든 조작은 터치와 음성으로 끝난다 |
| 컴포넌트 노출 | 서비스는 `exported="false"`, 런처 액티비티만 `exported="true"` |

## 9. 시뮬레이터 로그 표기

시뮬레이터 모드의 "SDK 호출 로그"는 실제 SDK 호출이 아니다. 같은 지점에서 Pleos 어댑터가 부르는 API 를 알아볼 수 있게 이름을 붙인 표기이며, 어댑터의 로그와 표기가 조금 다르다.

| 시뮬레이터 로그 | 같은 지점의 Pleos 어댑터 |
|---|---|
| `CarPropertyManager · IGNITION_STATE = ON / OFF` | 대응하는 API 없음. SDK 연결과 첫 값 수신으로 판단(`차량 값 수신 → 시동 ON 으로 봄`) |
| `CarPropertyManager · GEAR_SELECTION = P` | `Gear.onSelected → GEAR_PARK` (`GearSelectionListener`) |
| `CarPropertyManager · PERF_ODOMETER = 12,872km` | `Odometer.onDistanceDrivenUpdated → 12,872km` |
| `CarPropertyManager · SEAT_OCCUPANCY[조수석] = 착석` | `Seat.onSeatOccupancyUpdated(동승석) → 착석` |
| `Vehicle.carInfo.getVINInfo() → …` | `CarInfo.getVINInfo() → KMH…0001` (가운데를 가림) |
| `Vehicle.light.setAmbientLightBrightnessColor(PURPLE)` | `Light.setAmbientLightBrightnessColor(#FF8E6CC0 보라) → 적용` |
| `Vehicle.light.setAmbientLightBrightness(90)` | `Light.setAmbientLightBrightness(90%) → 적용` |
| `Vehicle.seat.setHvacSeatTemperature(DRIVER, 3)` | `Seat.setHvacSeatTemperature(운전석, 3단) → 적용` |
| `Vehicle.steeringWheel.setHvacSteeringWheelHeat(1)` | `SteeringWheel.setHvacSteeringWheelHeat(2단) → 적용` (단계는 차의 범위에 따름) |
| `Vehicle.HVAC.setHvacTemperature(DRIVER, 24.0℃)` | `HVAC.setHvacTemperature(24.0℃) → 적용` |
| `NaviHelper.requestRoute(낚시터)` | `NaviHelper.requestRoute(낚시터 · 위도 …, 경도 …)` |
| `NaviHelper · onDestinationArrived(낚시터) · 32km · 52분` | `NaviHelper.onDestinationArrived → 낚시터 · 32.0km · 52분` |
| `TextToSpeech.speak("…")`, `TextToSpeech · onDone` | `TextToSpeech.speak("…")`, `TextToSpeech.onDone` |
| `SpeechToText.request() · ko-KR` | `SpeechToText.request() · ON_DEVICE · ko-KR` |
| `FusedLocation.addProximityAlert(마트, 300m)` | `FusedLocation.addProximityAlert(마트, 반경 300m) → 등록됨` |

Pleos 모드에는 로그를 보여 주는 화면이 없다. 설정한 값은 Virtual Console 의 카드에서 확인한다.

## 10. 플랫폼에 제안하는 API

핵심 기능 다섯 가지는 현재 제공되는 SDK 만으로 동작한다. 아래는 지금 구현이 돌아가는 방식과, 같은 기능을 플랫폼이 직접 제공할 때의 제안이다.

| 제안 | 지금 구현 | 제안 내용 |
|---|---|---|
| 시동 이벤트 API | 시동 상태를 읽는 API 가 없어 SDK 연결과 첫 차량 값 수신을 시동 ON 으로 본다. 화면이 앞에 없어도 신호를 받도록 포그라운드 서비스가 연결을 유지한다 | 시동 순간 앱을 깨워 브리핑을 시작하게 한다 |
| 주행 세션 API | 기어, 누적 주행거리, 좌석, 내비 도착 이벤트를 앱(`TripTracker`)이 모아 한 번의 이동으로 묶는다. 도착은 NaviHelper 의 도착 이벤트로 안다 | 시동부터 정차까지를 한 번의 이동으로 묶어 출발지, 도착지, 거리, 시간을 한 번에 제공한다 |
| 클러스터 표시 API | 중앙 화면에만 표시한다 | 계기판에 "N년 전 오늘"과 편지 도착을 표시한다 |
| 사용자 프로필·현대자동차 계정 연동 API | 계정 원본을 앱 내부 JSON 파일로 대신하고, 좌석은 착석 수만 쓴다 | 가족이 각자 로그인해 공유받은 주제를 함께 꾸민다 |
| VIN·계정 연동 이어받기 API | 시동 때 `CarInfo.getVINInfo` 로 읽은 VIN 을 계정에 등록된 차와 비교해 앱이 이어받는다 | 명의 이전 때 차 안의 기록을 자동으로 지우고, 새 차 첫 시동에 자동으로 이어받는다 |
| Gleo AI 일정·메모 음성 등록 연동 | 브리핑 끝의 "메모할 것 있으세요?"에 이어 앱이 직접 SpeechToText 로 메모를 받는다 | Gleo AI 에 말한 일정과 메모를 「나이테」에 등록한다 |
