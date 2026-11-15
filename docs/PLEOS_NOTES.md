# PLEOS_NOTES — Pleos Connect 플랫폼 조사 노트 (`com.naitae.connect`)

- 조사일 2026-11-15. 기준: Emulator v2.1.0 / SDK 릴리즈 2026-10-19 (Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1, FusedLocation 1.1.3).
- 출처 표기: `〔S/…〕` = `https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/…`, `〔P/…〕` = `https://document.pleos.ai/docs/pleos-only/…`, `〔C/…〕` = `https://document.pleos.ai/docs/connect/guide/getting-started/application-development/…`
- 근거 등급: **문서** = 위 공식 문서 본문, **AAR** = Nexus 의 AAR 을 `javap -v` 로 직접 확인(어노테이션·생성자 바이트코드), **AAOS** = Pleos 문서에는 없고 Android Automotive 표준 지식(에뮬레이터에서 확인 필요).
- API Reference 하위 페이지(`S/Vehicle/vehicle/…` 등)는 브라우저에서만 본문이 렌더링됨(WebFetch 로는 빈 페이지). 본 노트의 Reference 인용은 페이지 JS 청크 원문에서 추출.

## 0. "이미 아는 것"과 어긋나거나 문서 예제를 그대로 쓰면 안 되는 점

| # | 내용 | 근거 |
|---|---|---|
| 1 | **`VehicleException` 은 2.3.2 AAR 에서 난독화되어 있음** → `ai.pleos.playground.vehicle.callback.exception.a`, `errorCode` 게터는 `a()`. 문서 예제 `if (e is VehicleException) e.errorCode` 는 컴파일 불가. `message` 는 `ErrorCode` 이름 문자열(예 `"ERROR_SECURITY_PERMISSION"`)이므로 `ErrorCode.valueOf(e.message)` 로 분기 (2.1절 스니펫) | AAR, `pleos-sdk-api/Vehicle.api.txt` 1603행 |
| 2 | **`Gear` 도메인은 문서(도메인 표·API Reference)에 없음.** AAR 에는 `Vehicle.getGear()` 존재, 권한은 `CAR_DRIVE` | AAR, 〔S/Vehicle/intro〕 |
| 3 | **NaviHelper 문서의 Java 예제는 좌표 순서가 틀림**(`new RouteInfo(127.03…, 37.48…)`). 3.0.5 에서 생성자가 `(latitude, longitude, …)` 로 바뀜. Kotlin 은 반드시 named argument 사용 | AAR, 〔P/release-note/sdk-release-note〕 |
| 4 | **앰비언트 라이트 set(밝기·색·스위치)의 요구 권한은 `CAR_LIGHTS`** (문서 일반 규칙 "set = `CONTROL_CAR_*`" 와 다름). `CONTROL_CAR_LIGHTS` 는 `setAmbientColorAutoByDriveModeEnabled` 등에만 붙어 있음 | AAR, Reference |
| 5 | `pleos.car.permission.FUSED_LOCATION` 은 **dangerous** (런타임 요청 필요, 미허용 시 `SecurityException`). Vehicle·Navi 권한은 normal | Reference |
| 6 | STT 문서는 `android.permission.RECORD_AUDIO` 를 요구하지 않음(`STT_SERVICE` 만). AAR 내부에도 `RECORD_AUDIO` 문자열 없음 | 문서, AAR |
| 7 | 버전·저장소·minSdk 28·Gradle 8+·Emulator v2.1.0(API 34)은 문서와 일치. 추가 정보: 문서 기준 Vehicle Service v2.3.21(샘플 앱 가이드는 2.3.22), AAR 매니페스트의 최소 서비스 버전 2.3.13. 샘플 앱은 JDK 17 / AGP 8.8.2 / Kotlin 2.0.0 / compileSdk·targetSdk 35 | 〔S/Vehicle/intro〕〔P/tutorial/vehicle-sdk-sample-app〕 |
| 8 | 심사 기준: "Vehicle SDK 퍼미션은 Pleos App Market 과 **사전 협의 및 승인된 경우에만** 선언 가능, 미승인 퍼미션 선언 앱은 심사 불통과" | 〔P/distribute/app-review-guide/review-functionality-performance-stability〕 |

## 1. Manifest

### 1.1 기능별 `<uses-permission>`

| 기능 | API (도메인) | 권한 `android:name` | 근거 |
|---|---|---|---|
| 기어 | `Gear.getCurrentGearSelectionState`, `registerGearSelection` | `pleos.car.permission.CAR_DRIVE` | AAR (문서에 Gear 없음) |
| 누적 주행거리 | `Odometer.getCurrentDistanceDriven`, `registerDistanceDriven` (km, `Float?`) | `pleos.car.permission.CAR_DRIVE` | 〔S/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/get-current-distance-driven〕 |
| 좌석 착좌 | `Seat.getCurrentSeatOccupancy`, `registerSeatOccupancy`, `checkSeatOccupancyCapabilities` | `pleos.car.permission.CAR_SEATS` | 〔S/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-occupancy〕 |
| 앰비언트 조회 | `Light.getAmbientLight{Brightness,BrightnessColor,Switch,State}`, `register…Listener`, `check…Capabilities` | `pleos.car.permission.CAR_LIGHTS` | 〔S/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-ambient-light-brightness-color〕 |
| 앰비언트 제어 | `Light.setAmbientLight{Brightness,BrightnessColor,Switch}` | `pleos.car.permission.CAR_LIGHTS` (에뮬레이터에서 `ERROR_SECURITY_PERMISSION` 이 나오면 `CONTROL_CAR_LIGHTS` 추가) | 〔S/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/set-ambient-light-brightness-color〕, AAR |
| 공조 조회·범위 | `HVAC.getHvac*`, `checkHvac*Capabilities`, `SteeringWheel.getHvacSteeringWheelHeatState`, `checkHvacSteeringWheelHeatCapabilities` | `pleos.car.permission.CAR_CLIMATE` | 〔S/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/check-hvac-temperature-capabilities〕 |
| 온도·풍량 제어 | `HVAC.setHvacTemperature`, `setHvacFanSpeed`, `setHvacPower` 등 | `pleos.car.permission.CONTROL_CAR_CLIMATE` | 〔S/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/set-hvac-temperature〕 |
| 스티어링 휠 열선 제어 | `SteeringWheel.setHvacSteeringWheelHeat` | `pleos.car.permission.CONTROL_CAR_CLIMATE` | 〔S/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/set-hvac-steering-wheel-heat〕 |
| 시트 열선 조회 | `Seat.getHvacSeatTemperature`, `checkSeatTemperatureCapabilities` | `pleos.car.permission.CAR_SEATS` | 〔S/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-temperature-capabilities〕 |
| 시트 열선 제어 | `Seat.setHvacSeatTemperature` (통풍은 `setHvacSeatVentilation`) | `pleos.car.permission.CONTROL_CAR_SEATS` | 〔S/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/set-hvac-seat-temperature〕 |
| VIN | `CarInfo.getVINInfo` (`String?`, null 가능). `getCRNInfo` 는 deprecated | `pleos.car.permission.CAR_INFO` | 〔S/Vehicle/vehicle/ai.pleos.playground.vehicle.api/car-info/get-v-i-n-info〕 |
| 경로 요청 | `NaviHelper.requestRoute`, `requestRouteWithWaypoints`, `cancelRoute`, `addWaypoint`, `removeWaypoint`, `changeRouteOption`, `requestReRoute` | `pleos.car.permission.NAVI_ROUTE_SEARCH` | 〔S/NaviHelper/declaring-permissions〕 |
| 즐겨찾기·목적지 조회 | `getBookmarkInfo`, `getDestinationInfo`, `getCurrentLocationInfo`, `getRecentDestinationInfo`, `getRouteStateInfo`, `getWaypointInfo`, `getTBTInfo`, `requestTrip` | `pleos.car.permission.NAVI_ROUTE` | 〔S/NaviHelper/declaring-permissions〕 |
| 목적지 도착 이벤트 | `addListener` → `onDestinationArrived` | `addListener` 자체에는 `@RequiresPermission` 없음(AAR). 이벤트 수신에 필요한 권한은 문서에서 확인 못 함 → `NAVI_ROUTE` 선언 상태로 사용 | AAR |
| 내비 화면 요청(미사용 예정) | `requestNavigationScreen` | `pleos.car.permission.NAVI_CUSTOM_MAP` | 〔S/NaviHelper/declaring-permissions〕 |
| TTS | `TextToSpeech` 전 API | `pleos.car.permission.TTS_SERVICE` | 〔S/TextToSpeech/declaring-permissions〕 |
| STT(마이크) | `SpeechToText` 전 API. 마이크는 `request()` 시 SDK/Speech Service 가 연다 | `pleos.car.permission.STT_SERVICE` (`RECORD_AUDIO` 필요 여부: 문서에서 확인 못 함) | 〔S/SpeechToText/declaring-permissions〕〔S/SpeechToText/start-and-stop〕 |
| 근접 알림·위치 | `FusedLocation.addProximityAlert`, `removeProximityAlert`, `getLastKnownLocation`, `registerFusedLocationCallback` | `pleos.car.permission.FUSED_LOCATION` (**dangerous**) | 〔S/FusedLocation/declaring-permissions〕〔S/FusedLocation/fused-location/ai.pleos.playground.location.fused/fused-location/add-proximity-alert〕 |

- 보호 수준: 위 표의 Vehicle `CAR_*`·`CONTROL_CAR_*` 와 `NAVI_ROUTE`·`NAVI_ROUTE_SEARCH` = normal (각 Reference 페이지 표기). `TTS_SERVICE`·`STT_SERVICE` 의 보호 수준: 문서에서 확인 못 함(오류 표에 "실행 환경의 권한 상태 확인" 문구만 있음).
- 권한 누락 시: Vehicle 은 `onFailed` 로 `ERROR_SECURITY_PERMISSION`, TTS/STT 는 `ErrorType.PERMISSION_DENIED`, FusedLocation 은 `SecurityException`. 〔S/Vehicle/vehicle-permission〕〔S/TextToSpeech/error-code〕
- `ACCESS_FINE_LOCATION` 등 Android 위치 권한과의 관계는 "이 SDK 문서에서 별도로 보장하지 않습니다". 〔S/FusedLocation/declaring-permissions〕

### 1.2 AAR 이 이미 선언(자동 병합)하는 항목 — 앱에서 다시 쓸 필요 없음

| AAR | 병합되는 선언 |
|---|---|
| Vehicle | `<uses-permission ai.pleos.playground.service.vehicle.VEHICLE_BINDING>`, `<queries><intent><action ai.pleos.playground.vehicle.VEHICLE_START>` |
| NaviHelper | `<queries><intent><action ai.pleos.playground.intent.action.NAVI_START>` |
| TextToSpeech / SpeechToText | `<uses-permission android.permission.INTERNET>`, `<queries><intent><action ai.pleos.playground.caas.intent.action.SPEECH_START>` (최소 CAAS 4.1.29) |
| FusedLocation | `<uses-permission ai.pleos.playground.service.location.fused.FUSED_LOCATION_BINDING>`, `<queries><package ai.pleos.playground.service.location.fused>`, `<uses-feature android:name="android.hardware.type.automotive" android:required="true">` |

근거: `C:\naitae\docs\pleos-sdk-api\*.AndroidManifest.xml`. 공식 문서는 `<queries>`·`<uses-feature>` 를 따로 요구하지 않음(문서에서 확인 못 함).

### 1.3 그 밖의 매니페스트 요소

| 항목 | 결론 | 근거 |
|---|---|---|
| `<uses-feature>` | 문서에 요구 없음. FusedLocation AAR 이 `android.hardware.type.automotive required=true` 를 병합함 | AAR |
| 런처 | `MAIN` + `android.intent.category.LAUNCHER` 액티비티(문서의 실행 명령·음악 앱 예제가 이 조합). 내비 앱만 `APP_MAPS` 사용 → 나이테는 해당 없음 | 〔P/vehicle-app-developer-guide/adb-command-reference-guide〕〔P/vehicle-app-developer-guide/navigation-app-development-guide〕 |
| Distraction Optimization(DO) | DO 미선언 앱은 **Drive 상태에서 사용 불가**(Parking 은 가능). 선언 문법은 Pleos 문서에 없음(용어집에 "앱의 distractionOptimized 선언"만 언급) | 〔P/vehicle-app-planning-guide/driving-restiction-policy〕 |
| DO 선언 문법 | AAOS 표준: 액티비티마다 `<meta-data android:name="distractionOptimized" android:value="true"/>` | AAOS |
| `android:appCategory` | 값이 정의된 것은 `game`/`audio`/`video` 뿐. 그 외(Lifestyle 등) 값: 문서에서 확인 못 함 → 지정하지 않음 | 〔P/vehicle-app-planning-guide/app-category〕 |
| `androidx.car.app.launchable` | Music/Audio 앱의 런처 노출용 메타데이터. 일반 앱 필요 여부: 문서에서 확인 못 함 | 〔P/vehicle-app-developer-guide/music-audio-app-developement-guide〕 |
| 로터리(CCP) | 사용자 설치 앱은 선택. 미지원이면 `<application>` 에 `<meta-data android:name="com.hmg.rotary.ROTARY_SUPPORTED" android:value="false"/>`, 필수 조작을 터치만으로 완결 | 〔P/vehicle-app-developer-guide/ccp-rotary-controller-development〕 |
| `screenOrientation` | 미지정 = portrait(창 1개), `landscape` = 창 2개 영역 사용. 두 형태 모두 반응형 필요 | 〔P/vehicle-app-planning-guide/app-structure-screen-model〕 |
| 컴포넌트 노출 | 외부 호출이 필요 없는 Activity/Service/Receiver/Provider 는 `exported=false` | 〔P/app-registration-guide/app-security-guide〕 |
| `android.car` 라이브러리 | `CarUxRestrictionsManager` 사용 시 Gradle `useLibrary("android.car")` (Pleos 문서에 설정 절차 없음) | AAOS |

```xml
<uses-permission android:name="pleos.car.permission.CAR_DRIVE" />
<uses-permission android:name="pleos.car.permission.CAR_SEATS" />
<uses-permission android:name="pleos.car.permission.CONTROL_CAR_SEATS" />
<uses-permission android:name="pleos.car.permission.CAR_LIGHTS" />
<uses-permission android:name="pleos.car.permission.CAR_CLIMATE" />
<uses-permission android:name="pleos.car.permission.CONTROL_CAR_CLIMATE" />
<uses-permission android:name="pleos.car.permission.CAR_INFO" />
<uses-permission android:name="pleos.car.permission.NAVI_ROUTE" />
<uses-permission android:name="pleos.car.permission.NAVI_ROUTE_SEARCH" />
<uses-permission android:name="pleos.car.permission.TTS_SERVICE" />
<uses-permission android:name="pleos.car.permission.STT_SERVICE" />
<uses-permission android:name="pleos.car.permission.FUSED_LOCATION" />
```

## 2. 초기화·수명주기 패턴

### 2.1 Vehicle 〔S/Vehicle/obtain-domain-object〕〔S/Vehicle/callback〕〔S/Vehicle/vehicle-error-code〕

```kotlin
val vehicle = Vehicle(context)
vehicle.initialize()
val door = vehicle.getDoor()
// ...
vehicle.release()
```

- `initialize()` 에는 결과 콜백이 없음. 초기화 반환이 서비스 연결 완료를 뜻하지 않음 → 첫 호출이 `ERROR_SERVICE_CONNECTION_FAILURE` 면 재시도. 〔P/tutorial/vehicle-sdk-sample-app〕
- 모든 get/set/capability 는 IPC 비동기(`onComplete`/`onFailed`). "비동기 콜백이 끝나기 전에 release()를 호출하지 않도록 주의".
- 네 가지 타입: `checkXxxCapabilities`(지원·범위) → `getCurrentXxx`/`getXxx`(조회) → `setXxx`(설정) → `registerXxx`/`unregisterXxx`(구독, **등록 때와 같은 리스너 인스턴스·같은 Area 로 해제**).
- 리스너는 모두 `VehicleListener` 를 상속하므로 `onFailed(e: Exception)` 구현 필수.
- set 의 `onComplete` 값은 "요청한 값"이지 반영 확인이 아님 → 조회 또는 리스너로 확인. 〔P/tutorial/vehicle-sdk-sample-app〕
- `Light.setAmbientLight*` 는 `onComplete`·`onFailed` 기본값이 없어 4개 인자 모두 필요. HVAC·Seat·SteeringWheel 의 set 은 기본값 있음(AAR).

```kotlin
// 문서 예제 (타입명만 참고 — VehicleException 은 AAR 에서 난독화되어 참조 불가)
onFailed = { e: Exception -> if (e is VehicleException) { /* e.errorCode */ } }
// 2.3.2 AAR 에서 동작하는 형태
fun Exception.toErrorCode(): ErrorCode? = runCatching { ErrorCode.valueOf(message ?: "") }.getOrNull()
```

- `ErrorCode`(`ai.pleos.playground.vehicle.constant`): `ERROR_NOT_SUPPORTED_PROPERTY`, `ERROR_NOT_SUPPORTED_AREA`, `ERROR_NOT_SUPPORTED_VALUE_TYPE`, `ERROR_SECURITY_PERMISSION`, `ERROR_SERVICE_CONNECTION_FAILURE`, `ERROR_INTERNAL_SERVICE`, `ERROR_DRIVING_RESTRICTED`, `ERROR_EMPTY_RESULT`, `ERROR_UNDEFINED`. "모든 onFailed가 VehicleException은 아닐 수 있습니다".

나이테가 쓰는 시그니처(AAR 확인, 패키지 접두 `ai.pleos.playground.vehicle.` 생략):

| 용도 | 시그니처 |
|---|---|
| 기어 | `vehicle.getGear().getCurrentGearSelectionState(onComplete: (VehicleGear?) -> Unit, onFailed)`; `registerGearSelection(GearSelectionListener)` → `onSelected(VehicleGear)`. 값: `GEAR_UNKNOWN/NEUTRAL/REVERSE/PARK/DRIVE/GEAR_1..9` |
| 주행거리 | `vehicle.getOdometer().getCurrentDistanceDriven((Float?) -> Unit, onFailed)`; `registerDistanceDriven(DistanceDrivenListener)` → `onDistanceDrivenUpdated(Float?)` (km) |
| 착좌 | `vehicle.getSeat().getCurrentSeatOccupancy(SeatArea(SeatValue.ROW_1_LEFT), (Boolean?) -> Unit, onFailed)`; `registerSeatOccupancy(area, SeatOccupancyListener)` → `onSeatOccupancyUpdated(Boolean?)`. `SeatValue`: `ROW_{1,2,3}_{LEFT,CENTER,RIGHT}`. 운전석이 어느 값인지: 문서에서 확인 못 함 → `checkSeatOccupancyCapabilities` 가 돌려주는 `List<SeatArea>` 사용 |
| 앰비언트 | `vehicle.getLight().setAmbientLightBrightnessColor(area, value: Int /*32-bit ARGB, alpha 0xFF 고정*/, onComplete: (SeatArea, Int) -> Unit, onFailed)`; `setAmbientLightBrightness(area, Int, …)`(범위는 `checkAmbientLightBrightnessCapabilities` 의 `AreaBounds<Int>`); `setAmbientLightSwitch(area, LightSwitch.ON/OFF, …)`; 지원 Area 는 `checkAmbientLightBrightnessColorCapabilities` → `List<SeatArea>` |
| 온도 | `vehicle.getHVAC().setHvacTemperature(area, value: Float, unit: TemperatureUnit.CELSIUS, onComplete, onFailed)`. 범위는 차량별 → `checkHvacTemperatureCapabilities { config, Map<SeatArea, AreaBounds<Float>>(°C), HvacTemperatureConfigArray? -> }` |
| 풍량 | `setHvacFanSpeed(area, Int, …)`, 범위 `checkHvacFanSpeedCapabilities` → `Map<SeatArea, AreaBounds<Int>>` (2.3.2 에서 `List<Int>` → `AreaBounds<Int>` 로 변경) |
| 시트 열선 | `vehicle.getSeat().setHvacSeatTemperature(area, value: Int /*단계*/, …)`, 범위 `checkSeatTemperatureCapabilities` |
| 핸들 열선 | `vehicle.getSteeringWheel().setHvacSteeringWheelHeat(value: Int, …)`, 범위 `checkHvacSteeringWheelHeatCapabilities { config, AreaBounds<Int> -> }` |
| VIN | `vehicle.getCarInfo().getVINInfo((String?) -> Unit, onFailed)` |

- `CapabilityConfig.accessType`: `NONE/READ/WRITE/READ_WRITE`. 범위는 `CapabilityConfig` 가 아니라 두 번째 인자(`AreaBounds.minValue/maxValue`)에서 읽음. 〔S/Vehicle/check-capability〕
- 2.3.2 부터 `SeatArea` 등 Area 는 내용 기반 `equals/hashCode` → `Map` 키로 사용 가능. 〔P/release-note/sdk-release-note〕

### 2.2 NaviHelper 〔S/NaviHelper/initialization-and-release〕〔S/NaviHelper/event-listeners〕

```kotlin
val naviHelper = NaviHelper(applicationContext)
naviHelper.initialize(
    onConnected = { /* 리스너를 등록하고 API를 호출합니다. */ },
    onFailed = { throwable -> /* 초기 연결 재시도가 모두 실패했습니다. */ }
)
// 해제
naviHelper.removeListener(naviEventListener)
naviHelper.release()
```

- `NaviHelperEventListener` 는 전부 default 메서드 → 필요한 콜백만 override. `addListener` 는 `onConnected` 이후.
- `release()` 후 재사용하려면 객체를 새로 생성·초기화.

### 2.3 TextToSpeech 〔S/TextToSpeech/intro〕〔S/TextToSpeech/initialization-and-release〕〔S/TextToSpeech/event-listeners〕

```kotlin
val textToSpeech = TextToSpeech(applicationContext, Mode.ON_DEVICE)   // 생략 시 Mode.HYBRID
textToSpeech.initialize(object : OnInitializeListener {
    override fun onSuccess() { /* TextToSpeech API를 사용할 수 있습니다. */ }
    override fun onFailure(errorType: ErrorType) { }
})
textToSpeech.addEventListener(eventListener)   // onReady/onStart/onUpdatedRms(Double)/onDone/onStop/onError(ErrorType)
textToSpeech.setLocale(Locale.KOREA)
textToSpeech.speak("안녕하세요.")
// 해제
textToSpeech.removeEventListener(eventListener); textToSpeech.release()
```

- 패키지: `ai.pleos.playground.tts.TextToSpeech`, `…tts.constant.{Mode, ErrorType}`, `…tts.listener.{EventListener, OnInitializeListener, OnServerConnectionListener}`.

### 2.4 SpeechToText 〔S/SpeechToText/intro〕〔S/SpeechToText/result-listeners〕〔S/SpeechToText/start-and-stop〕

```kotlin
val speechToText = SpeechToText(applicationContext, Mode.ON_DEVICE)   // 생략 시 Mode.HYBRID
speechToText.initialize(object : OnInitializeListener { /* onSuccess / onFailure(ErrorType) */ })
speechToText.addListener(resultListener)   // request() 전에 등록
speechToText.setLocale(Locale.KOREA)
speechToText.request()                     // 마이크 활성화 + 인식 시작
speechToText.stop()                        // 마이크 종료 → onEndedRecognition()
speechToText.removeListener(resultListener); speechToText.release()
```

- `ResultListener`: `onReady()`, `onStartedRecognition()`, `onUpdated(stt: String, completed: Boolean)`, `onUpdatedEpdData(on: Long, off: Long)`, `onUpdatedRms(rms: Double)`, `onEndedRecognition()`, `onError(ErrorType)`. 패키지 `ai.pleos.playground.stt.*`.

### 2.5 FusedLocation 〔S/FusedLocation/initialization-and-release〕

```kotlin
fusedLocation = FusedLocation(this)          // Application.onCreate
if (fusedLocation.isSupported()) {
    fusedLocation.initialize()
} else {
    // 위치 관련 UI와 기능을 비활성화합니다.
}
// 종료: unregisterFusedLocationCallback(listener) → release()
```

- `initialize()` 를 호출했으면 `release()` 필수. `release()` 후 같은 인스턴스 재사용 금지.

## 3. TTS / STT 모드

| 항목 | 내용 | 출처 |
|---|---|---|
| 모드 | `HYBRID`(기본, 서버 우선·네트워크 실패 시 On-device 자동 전환), `SERVER`(서버만, 실패 시 오류 이벤트), `ON_DEVICE`(차량 엔진, 네트워크 불필요) | 〔S/TextToSpeech/intro〕〔S/SpeechToText/intro〕 |
| 앱 등록이 필요한 경우 | `SERVER` 는 필수, `HYBRID` 도 서버 합성/인식을 쓰려면 필요. **`ON_DEVICE` 만 쓰면 등록 불필요** | 〔S/TextToSpeech/app-registration〕 |
| 등록 방법 | 초기화 완료 후 `registerApp(clientID = …, clientSecret = …, listener = OnServerConnectionListener)`; `onConnected()` 이후에 서버 기반 요청. Client ID/Secret 은 Pleos Playground 의 My Project 생성 시 발급 | 〔S/TextToSpeech/app-registration〕〔S/SpeechToText/app-registration〕 |
| 로케일 | `setLocale(Locale.KOREA)` → ko-KR. 지원: ko-KR, en-US, fr-CA, es-US, es-MX. 미호출 시 같은 앱의 이전 설정 → 없으면 시스템 로케일. 요청 전에 설정한 값이 그 요청에 적용 | 〔S/TextToSpeech/set-locale〕〔S/SpeechToText/set-locale〕 |
| 화자 | `setSpeakerType(type: String)`. 한국어 `seoyoon`, `eunwoo`, `haeun`, `sanghyeok` / 영어 `lyra`, `finley` / 스페인어 `iris`, `arlo` / 프랑스어 `sabine`, `noah`. 미호출 시 앱 저장값 → Gleo AI 화자 설정 → `haeun` | 〔S/TextToSpeech/text-to-speech/ai.pleos.playground.tts/text-to-speech/set-speaker-type〕 |
| TTS 스트리밍 | `speak(text, isFinal = true)`. 여러 조각을 한 발화로 보낼 때 마지막만 `isFinal = true`: `speak("안녕하세요. ", isFinal = false)` → `speak("…", isFinal = true)` | 〔S/TextToSpeech/start-and-stop〕 |
| TTS 중지 | `stop()` → `EventListener.onStop()`. 정상 종료는 `onDone()` | 〔S/TextToSpeech/start-and-stop〕 |
| TTS 오디오 | `setAudioAttributes(AudioAttributes)`, `setAudioZoneId(Int)` 제공. 오디오 포커스를 SDK 가 요청하는지 앱이 요청해야 하는지: 문서에서 확인 못 함 | 〔S/TextToSpeech/text-to-speech/ai.pleos.playground.tts/text-to-speech/set-audio-attributes〕 |
| STT 시작·종료 | `request()` → 마이크 열림, `stop()` → 마이크 닫힘·`onEndedRecognition()`. 발화 종료(EPD) 시 자동 종료 여부: 문서에서 확인 못 함 → `onUpdated(_, completed = true)` 수신 시 앱이 `stop()` 호출 | 〔S/SpeechToText/start-and-stop〕 |
| STT 외부 PCM | `sendAudio(ByteArray)` + `completeAudioSend()` 는 **`ON_DEVICE` 전용**(다른 모드에서는 무시, `onError` 도 없음). 16 kHz·16-bit·mono 기준 100 ms = 3,200 byte. 이 경로에서는 `onReady`·`onUpdatedRms` 미호출, `request()` 불필요 | 〔S/SpeechToText/sending-audio-data〕〔S/SpeechToText/speech-to-text/ai.pleos.playground.stt/speech-to-text/send-audio〕 |
| STT 한계 | 텍스트 변환만 하고 명령 실행은 하지 않음(명령 연동은 Gleo Interface) | 〔S/SpeechToText/intro〕 |

처리할 `ErrorType` (TTS·STT 공통 enum 이름, `when` 에 `else` 필수) 〔S/TextToSpeech/error-code〕〔S/SpeechToText/error-code〕

- `PERMISSION_DENIED` → 매니페스트 `TTS_SERVICE`/`STT_SERVICE` 확인. `UNAUTHENTICATED` → Client ID/Secret 확인(SERVER·HYBRID).
- `NETWORK_ERROR`, `TIMEOUT` → 네트워크 복구 후 재시도. `ON_DEVICE_ENGINE_ERROR` → 엔진 상태 확인, HYBRID/SERVER 검토.
- `MIC_ERROR`(STT) → 마이크 상태·센서 개인정보 보호 설정 확인, 터치 입력으로 대체. `UNAVAILABLE_LANGUAGE` → 지원 로케일로 변경.
- `INVALID_REQUEST_ERROR` → 호출 순서·중복 요청 확인. `NOT_SUPPORTED_API` → 모드에 맞지 않는 API. `AUDIO_ERROR`, `SERVER_INTERNAL_ERROR`, `UNKNOWN` → 로그 후 재시도.

## 4. NaviHelper

### 4.1 경로 요청 〔S/NaviHelper/destinations〕〔S/NaviHelper/navi-helper/ai.pleos.playground.navi.data/route-info/entry〕

```kotlin
naviHelper.requestRoute(
    RouteInfo(
        latitude = 37.48544722,
        longitude = 127.03636666,
        displayLongitude = null,
        displayLatitude = null,
        poiName = "42dot",
        poiId = "36khp37hh8gqcm5nqhe5",
        poiSubId = "0",
        address = "서울시 강남구 남부순환로 2621"
    )
)
```

- 생성자(AAR 확인): `RouteInfo(latitude: Double, longitude: Double, displayLatitude: Double? = null, displayLongitude: Double? = null, poiName: String, poiId: String, address: String, poiSubId: String, routeOption: RouteOption = RouteOption.RECOMMENDED, poiProvider: String = "", isMobileRequest: Boolean = false)`.
- **좌표 순서는 위도 → 경도.** 위치 인자로 넘기지 말고 named argument 사용(0절 3번).
- `poiName`·`poiId`·`address`·`poiSubId` 는 non-null·기본값 없음. POI ID 가 없는 임의 좌표에 빈 문자열을 넣어도 되는지: 문서에서 확인 못 함(에뮬레이터 검증 필요).
- `RouteOption`: `RECOMMENDED`, `SHORT_DISTANCE`, `SHORT_TIME`, `FREE`, `HIGHWAY`, `COMFORTABLE`, `SCHOOL_ZONE`. 〔S/NaviHelper/routes〕
- 경유지 포함: `requestRouteWithWaypoints(RouteWithWaypointsRequest(destination = RouteInfo(…), waypoints = listOf(RequestWaypointInfo(latitude, longitude, poiName, waypointIndex = WaypointIndex.FIRST, poiId, address, poiSubId))))`. 경유지 최대 2개(`FIRST`, `SECOND`; 전체 삭제 `ALL`). 〔S/NaviHelper/route-with-waypoints〕〔S/NaviHelper/waypoint-settings〕
- "requestRoute() 호출이 정상적으로 수행되더라도 경로 탐색 완료를 의미하지 않습니다." 결과는 `onRouteStarted(RouteStartInfo)` → `onRoutingCompleted()` / `onDestinationChanged()` / `onError(NaviErrorCode)`. `onRouteEnded()` 는 deprecated.
- 조회 API 는 값을 반환하지 않고 대응 콜백으로만 전달. 실제 전달 이벤트는 Navigation 앱 구현·상태에 따라 달라질 수 있음.

### 4.2 `onDestinationArrived` 〔S/NaviHelper/navi-helper/ai.pleos.playground.navi.data/destination-arrived-info/entry〕

- `onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo)`
- `DestinationArrivedInfo(duration: Int /*총 소요 시간, 초*/, distance: Int /*총 주행 거리, m*/, name: String, address: String)`
- **좌표·POI ID 는 없음.** 도착지 좌표가 필요하면 경로 설정 시 `getDestinationInfo()` → `onDestinationInfo(DestinationInfo)` (`latitude`, `longitude`, `name`, `address`, `remainDistance`, `remainTime`, `poiId` …)를 미리 저장하거나, 도착 시 `getCurrentLocationInfo()` → `onCurrentLocationInfo(CurrentLocationInfo(latitude, longitude, heading, address, routeId, currentRouteIndex))` 사용.
- 내비 상태: `onNavigationStatus(NavigationStatus)` — `INACTIVE`, `INITIALIZING`, `FREE_DRIVE`, `ROUTE_DRIVE`.

### 4.3 즐겨찾기 〔S/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/get-bookmark-info〕〔S/NaviHelper/navi-helper/ai.pleos.playground.navi.data/bookmark-info/entry〕

- `naviHelper.getBookmarkInfo()` → `onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>)`.
- `BookmarkInfo(latitude, longitude, displayLatitude?, displayLongitude?, name: String, poiId: String, poiSubId: String?, address: String, category: BookmarkType, poiProvider: String = "")`. `BookmarkType`: `HOME`, `OFFICE`, `EV_CHARGING_STATION`, `DEFAULT`.
- 변경 알림 `onBookMarkChanged()` 는 인자가 없음 → 받으면 `getBookmarkInfo()` 재호출.

### 4.4 오류 〔S/NaviHelper/error-handling〕

- `NaviErrorCode`: `CAUSE_LOW_BATTERY`, `CAUSE_SEARCH_ROUTE_OVER_DISTANCE`, `CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE`, `CAUSE_SEARCH_ROUTE`, `CAUSE_SEARCH_TRIP`(AAR 에만 있음).
- NaviHelper 는 "내비게이션이 탑재된 차량에 한해 전체 호환". 탑재 여부 확인 방법은 비공개 → `initialize` 의 `onFailed` 를 미탑재로 간주하고 기능 숨김. 〔P/vehicle-app-planning-guide/api-compatibility-availability〕〔P/vehicle-app-developer-guide/vehicle-app-faq〕

## 5. FusedLocation 근접 알림

| 항목 | 내용 | 출처 |
|---|---|---|
| 지원 확인 | `isSupported(): Boolean` — Fused Location Service 패키지 존재 여부만 확인(초기화·연결 상태와 무관, 서비스 시작 안 함). false 면 위치 기능 전체 비활성화 | 〔S/FusedLocation/fused-location/ai.pleos.playground.location.fused/fused-location/is-supported〕 |
| 등록 | `addProximityAlert(request: ProximityAlertRequest, listener: ProximityAlertListener)` | 〔S/FusedLocation/fused-location/ai.pleos.playground.location.fused/fused-location/add-proximity-alert〕 |
| 요청 모델 | `ProximityAlertRequest(latitude: Double /*-90..90*/, longitude: Double /*-180..180*/, radiusMeters: Float /*> 0*/, transitionTypes: Set<ProximityTransition> /*비어 있으면 안 됨*/)` | 〔S/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/proximity-alert-request/entry〕 |
| 전이 | `ProximityTransition.ENTER`, `ProximityTransition.EXIT` | 〔S/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/proximity-transition/entry〕 |
| 리스너 | `onRegistered(alertId: String)`, `onTransition(event: ProximityAlertEvent(alertId: String, transition: ProximityTransition))`, `onError(exception: Exception)`(잘못된 요청도 여기로) | 〔S/FusedLocation/fused-location/ai.pleos.playground.location.fused.listener/proximity-alert-listener/entry〕 |
| 해제 | `removeProximityAlert(alertId)` — `onRegistered` 로 받은 ID. 모르는 ID·이미 해제된 ID 는 무시 | 〔S/FusedLocation/fused-location/ai.pleos.playground.location.fused/fused-location/remove-proximity-alert〕 |
| 권한 | add/remove 모두 `FUSED_LOCATION`(dangerous). 미허용 시 `SecurityException` throw → 호출 전 `checkSelfPermission` + 런타임 요청 | 위와 같음 |
| 차단 안내 | `showLocationAccessBlockedPopup()` — 차량 개인정보 설정으로 위치가 막혔을 때 안내 팝업(허용시키는 API 아님) | 〔S/FusedLocation/declaring-permissions〕 |
| 위치 | `LocationData(latitude, longitude, altitude: Double, heading: Float, confidence: Float, timestampMillis: Long)`. `onChanged(LocationData?)` 는 null 가능 | 〔S/FusedLocation/location-data-access〕 |
| 미확인 | 동시 등록 개수 한도, 프로세스 종료·재부팅 후 알림 유지 여부, 백그라운드 전달 여부: 문서에서 확인 못 함 → 앱 시작 때마다 재등록 | — |

## 6. 주행 중 제약·성능·오디오·백그라운드

### 6.1 주행 규제(UX Restrictions) 〔P/vehicle-app-developer-guide/driving-restriction-development-guide〕〔P/vehicle-app-planning-guide/driving-restiction-policy〕

- 앱은 AAOS `CarUxRestrictionsManager.registerListener()` → `onUxRestrictionsChanged()` 로 받은 `CarUxRestrictions` 를 기준으로 제한을 **직접** 적용. "DO를 선언한 앱도 … 기능별 UX 제한을 직접 적용해야 합니다."

```java
if (mCurrentCarUxRestrictions.isRequiresDistractionOptimization()
        && (mCurrentCarUxRestrictions.getActiveRestrictions()
            & CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO) != 0) {
    stopPlayingVideo();
}
```

- "그외 지역"(한국 포함으로 해석, 표에 한국 열 없음)의 Driving 상태 표:

| 기능 (플래그) | Driving |
|---|---|
| 키보드 `UX_RESTRICTIONS_NO_KEYBOARD`(8), 다이얼패드 `NO_DIALPAD`(1), 검색 `NO_FILTERING`(2), 내비 검색 | 허용 |
| 미디어 앱 로그인·설정 `NO_SETUP`(64) | 허용 |
| 디지털키·도어·시트·차량 설정 `NO_SETUP`(64) | **제한** |
| 동영상 `NO_VIDEO`(16) | **제한** |
| QR 로그인 `NO_SETUP`(64) | **제한** |
| 대용량 텍스트 `LIMIT_CONTENT`(32), `LIMIT_STRING_LENGTH`(4) | **제한** |

- 북미는 키보드·다이얼패드도 제한(검색은 음성 한정), 유럽은 미디어 앱 로그인·설정도 제한. 지역 코드는 시스템 프로퍼티 `product.sw.target_region` = `KOR`/`EUR`/`USA`/`CAN`. 〔P/vehicle-app-developer-guide/vehicle-app-faq〕
- 규제 해제 조건(북미 외): 시동 OFF, 또는 5 km/h 이하이면서 (기어 P | Parking Brake ON | Auto Hold 활성). 그 외에는 규제 ON. 〔P/infolink-emulator/driving-restiction-policy〕
- 플랫폼이 차량 제어 자체를 막을 수 있음: Vehicle `ERROR_DRIVING_RESTRICTED` = "주행 중 기능 제한" → 안내 후 중단. 〔S/Vehicle/vehicle-error-code〕
- 설계 원칙(주행 중): 동영상·애니메이션·움직이는 이미지 최소화, 자동 화면 전환·다단 메뉴 지양, 큰 터치 영역·높은 대비·텍스트 최소화, 키보드보다 프리셋·최근 항목·음성 우선, 헤드업 알림은 운전 관련만. 〔P/vehicle-app-planning-guide/driver-distraction-guideline〕〔P/distribute/app-review-guide/review-driver-distraction-elements〕
- 동영상·게임·브라우저 기능 앱은 정차 중에만 사용, 운전 시작 시 UI 숨김·일시 중지 해제 불가. 〔P/distribute/app-review-guide/review-driver-distraction-elements〕

### 6.2 성능 한도 〔P/vehicle-app-planning-guide/performance-resource-mgmt〕〔P/distribute/app-review-guide/review-functionality-performance-stability〕

| 항목 | 기준 |
|---|---|
| CPU (내비 아닌 앱, 주행 중) | **5% 미만** |
| CPU (모든 앱, 정차 중) | 25% 이하 (내비 앱 주행 중은 15% 이하) |
| RAM | **400 MB 이하** |
| 터치 응답 | 터치 → 터치 효과까지 0.25초 이내 |
| 실행·콘텐츠 로드 / 버튼 반응 | 각 10초 이내 / 2초 이내(넘으면 진행 중 UI 표시) |
| 복귀·화면 | 백그라운드 복귀 시 마지막 상태 복원, 레터박스·호환성 모드 금지, 해상도 전환 시 상태 손실 없음 |
| 패키지 | APK, 최대 600 MB 〔P/distribute/app-submission-guide〕 |

### 6.3 오디오 포커스 〔P/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development〕

- 모든 오디오 스트림은 재생 전 `AudioAttributes` + `AudioFocusRequest` 로 포커스 요청. 음악과 공존(C)하려면 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`.
- 포커스 변경 처리 필수: `AUDIOFOCUS_GAIN` 재생 / `AUDIOFOCUS_LOSS` 즉시 정지 / `AUDIOFOCUS_LOSS_TRANSIENT` 일시정지 / `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` 감쇄.
- 매트릭스 요지: CALL 중 MUSIC 요청은 거부(R), EMERGENCY 재생 중 대부분 요청 거부, NAVIGATION↔MUSIC 은 공존. Gleo 호출로 `AUDIOFOCUS_LOSS_TRANSIENT` 를 받으면 즉시 중단하고 필요 시 포커스 반납.
- 컨텍스트 매핑(usage → CarAudioContext): `MEDIA`·`GAME`·`UNKNOWN` → MUSIC, `ASSISTANCE_NAVIGATION_GUIDANCE` → NAVIGATION, `ASSISTANT` → VOICE_COMMAND, `NOTIFICATION*` → NOTIFICATION, `ASSISTANCE_SONIFICATION` → SYSTEM_SOUND.
- Suspend(시동 OFF 후 저전력) 진입 시: 재생 즉시 중단 → 포커스 `abandon` → 상태 저장 → 리소스 해제. Resume 시 자동 복원 금지. 〔P/vehicle-app-developer-guide/audio-focus-development-guide/suspend-mode-audio-focus〕
- 음성 안내 시작 전 선행 묵음 150~200 ms 권장(첫 음절 손실 방지). SDK TTS 에 묵음을 넣는 방법: 문서에서 확인 못 함. 〔P/vehicle-app-developer-guide/voice-guidance-preroll-silence-guide〕

### 6.4 포그라운드 서비스·부팅 리시버 〔P/vehicle-app-developer-guide/bg-to-cch-transition-guide〕

- **필수 구현**: 화면 미점유 + 오디오 미재생 + 백그라운드에서 사용자에게 제공 중인 기능 없음 → `stopForeground(...)`, `stopSelf()` 로 FGS 종료하고 cached(CCH) 전환 허용. 미사용 상태의 FGS 유지는 심사 NG.
- "실제 사용자 동작/재생 없이 FGS를 시작/유지하면 안 되며" (Cold Boot 원칙). 검증: `adb shell dumpsys activity lru`.
- 백그라운드 전환 시 화면 갱신·애니메이션·불필요한 센서 수집 중단, 타이머·폴링·네트워크 최소화. 〔P/vehicle-app-planning-guide/app-structure-screen-model〕
- `BOOT_COMPLETED` 리시버·부팅 자동 실행 규정: 문서에서 확인 못 함 → 위 원칙상 부팅 시 FGS 를 띄우지 않음. 주행 기록을 백그라운드에서 계속 수집하는 것이 "사용자 제공 기능"으로 인정되는지: 문서에서 확인 못 함.

## 7. 에뮬레이터·Virtual Console

### 7.1 설치·AVD 〔C/setup-connect-sdk-emulator〕〔C/configure-pleos-connect-emulator〕

- SDK Update Site: Windows/Linux `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`, Mac `…/crp-arm64-sys-img.xml`. 이전 버전 이미지는 먼저 삭제.
- AVD: Form Factor **Automotive** → `Pleos Connect v2.1.0` → API 34 `Pleos Connect v2.1.0 APIs`. Default boot **Cold**, RAM 4 GB, Graphics Hardware.
- **Cold boot 필수**: "quick boot로 설정할 경우에는 VHAL을 이용할 수 없습니다."
- GPU 최소 RTX 3060 Laptop 6GB / RX 6600M 8GB 급.
- 마이크: 에뮬레이터 Extended Controls → Microphone 에서 호스트 오디오 입력 수신을 켬(STT 테스트용).
- 기본 언어 한국어(차량 설정 > 일반 설정 > 언어). Bluetooth 동글 미지원. 에뮬레이터 코덱은 실차와 다름.
- `android.os.Build.DEVICE`(`CONNECT_S/L/W`)는 실차에서만 유효. 〔P/vehicle-app-developer-guide/vehicle-app-faq〕

### 7.2 Virtual Console 실행 〔C/use-vehicle-emulator〕

```
adb forward tcp:8088 tcp:8088
```

- PC 브라우저에서 `http://127.0.0.1:8088/` → Emulation Controls. Emulator v2.1.0 이상 필요. 접속 불가 시 `adb devices` 확인 후 포워딩 재실행, 8088 포트 점유 확인.

### 7.3 주입 가능한 신호 〔P/app-test-guide/virtual-console-guide〕

| Domain | 카드 | 범위 |
|---|---|---|
| Motion | Gear Selection | P, R, N, D |
| Motion | Odometer | 0~1,000,000 km, 1 km 단위 |
| Motion | Steering Wheel Heat | Off, Low, Medium, High (그 외 Brake Pedal 0~100%, Steering Angle -540~540°) |
| Convenience | Seat Occupancy | 운전석·동승석·뒷좌석 좌우, Empty/Occupied |
| Convenience | Ambient Brightness | 0~100%, 1% 단위 |
| Convenience | Ambient Color | **Cyan, Blue, Purple, White** 4종 |
| Convenience | Door/Window/Trunk/Cabin·Reading Lights/Wiper/Turn Signal/ETCS 등 | 가이드 표 참조 |
| Climate | HVAC Power, Air Conditioning, HVAC Auto, HVAC Dual, Air Recirculation, Defroster, Air Cleaner | Off/On |
| Climate | Fan Speed | 0~8단계 |
| Climate | Target Temperature | 운전석/동승석, 16~30°C, 0.5°C 단위 |
| Climate | Seat Temperature / Fan Direction | 운전석/동승석, 통풍 3단계·Off·열선 3단계 / Face, Floor, Defrost |
| ADAS·Safety·Energy | Cruise Control State/Target Speed, Telltales(주차 브레이크·안전벨트 등), Remaining Range, Battery SOC 등 | 가이드 표 참조 |

### 7.4 한계

- 카드 목록에 **내비게이션(목적지 설정·도착), 위치(GPS), 차량 속도, VIN 카드가 없음** → `onDestinationArrived`, 근접 알림 `ENTER/EXIT`, 속도 기반 주행 규제는 Virtual Console 로 재현 불가. 대체 주입 수단(에뮬레이터 Location 확장 컨트롤 등)이 FusedLocation 에 반영되는지: 문서에서 확인 못 함.
- 에뮬레이터에 Navigation 앱이 있어 NaviHelper 이벤트가 오는지: 문서에서 확인 못 함 ("실제로 전달되는 이벤트는 Navigation 앱의 구현과 현재 상태에 따라 달라질 수 있습니다").
- 앰비언트: 색은 4종 프리셋만, 스위치(On/Off)·`AmbientColorAutoByDriveMode` 카드 없음. 임의 ARGB 를 set 했을 때 에뮬레이터가 받아들이는 값 범위: 문서에서 확인 못 함.
- 좌석: 2열 가운데·3열 없음. Seat Temperature 는 1열만.
- 기어는 P/R/N/D 만(수동 단수 없음). 기어 D 만으로 `CarUxRestrictions` 가 바뀌는지: 문서에서 확인 못 함.
- 폼 팩터 테스트(다른 해상도·방향)는 별도: Go 1.22+ 로 Host Display Bridge(`go run . --avd "<AVD 이름>"`, 포트 8095·8096) 실행 후 에뮬레이터의 FormFactorConsole 앱에서 가상 디스플레이 생성. 에뮬레이터를 별도 창으로 실행해야 함. 〔P/app-test-guide/form-factor-test-guide〕

## 8. 앱 구현 체크리스트

1. 매니페스트에 1.3절 권한 12개 선언. `<queries>`·`INTERNET`·바인딩 권한·automotive feature 는 AAR 병합에 맡김.
2. `FUSED_LOCATION` 은 런타임 권한 요청 후에만 `addProximityAlert`/위치 API 호출(`SecurityException` 방어).
3. Vehicle 오류는 `ErrorCode.valueOf(e.message)` 로 해석. `VehicleException` 타입을 import 하지 않음.
4. 모든 Vehicle set 전에 `checkXxxCapabilities` 로 `accessType` 과 `AreaBounds`·지원 Area 를 받아 캐시하고, 범위 밖 값·미지원 Area 는 호출하지 않음.
5. Vehicle 리스너는 등록한 인스턴스·Area 그대로 해제하고, 콜백이 모두 끝난 뒤 `release()`.
6. Vehicle `initialize()` 직후 실패(`ERROR_SERVICE_CONNECTION_FAILURE`)는 지연 재시도.
7. `RouteInfo`·`RequestWaypointInfo` 는 named argument, 위도 → 경도.
8. 도착 기록의 좌표는 `onDestinationInfo`(경로 설정 시)에서 확보. `onDestinationArrived` 는 이름·주소·소요 시간(초)·거리(m)만 줌.
9. TTS/STT 는 `Mode.ON_DEVICE` 로 시작(앱 등록·네트워크 불필요), `setLocale(Locale.KOREA)`, 화자는 `haeun` 등 한국어 4종.
10. STT: `addListener` → `request()`, `completed = true` 수신 또는 화면 이탈 시 `stop()`. `MIC_ERROR`·`PERMISSION_DENIED` 는 터치 입력으로 대체.
11. 액티비티에 DO 메타데이터를 선언하고 `CarUxRestrictionsManager` 리스너로 `NO_SETUP`·`NO_VIDEO`·`LIMIT_CONTENT`·`LIMIT_STRING_LENGTH` 활성 시 해당 UI 를 축소·비활성화. `ERROR_DRIVING_RESTRICTED` 는 안내 문구로 처리.
12. 주행 중 CPU 5% 미만·RAM 400 MB 이하: 폴링 대신 `registerXxx` 구독, 백그라운드에서 화면 갱신·애니메이션 중단.
13. 자체 오디오를 재생하면 포커스 요청·상실 처리, Suspend 진입 시 `abandon`. 사용자에게 제공 중인 기능이 없으면 FGS 종료, 부팅 시 FGS 미기동.
14. portrait(창 1개)·landscape(창 2개) 모두에서 레이아웃이 깨지지 않게 하고, 복귀 시 마지막 상태 복원. 로터리 미지원이면 `com.hmg.rotary.ROTARY_SUPPORTED=false`.
15. 검증: AVD Cold boot → `adb forward tcp:8088 tcp:8088` → Virtual Console 에서 Gear·Odometer·Seat Occupancy·Ambient·Climate 카드로 구독 콜백과 set 반영을 확인. 내비 도착·근접 알림·속도 규제는 7.4절의 미확인 항목으로 따로 검증.
