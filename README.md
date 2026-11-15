# ParkTrace

> 주차장에 들어서면 차량 카메라 비전으로 요금 안내 표지와 층·구역 표지를 읽어, 주차 요금과 내 차 위치("지하 1층 C-15 구역")를 화면과 음성으로 알려 주는 Pleos Connect 차량용 주차 도우미 앱. 현재 버전은 카메라 인식 결과를 가정한 시연용입니다 (차량 카메라는 공모전 공개 SDK 범위 밖).

현대자동차그룹 차량용 앱 개발 공모전(Pleos App Market) 출품작입니다. 팀명: ParkTrace.

## 제출 정보

| 항목 | 내용 |
|---|---|
| 앱 이름 | ParkTrace |
| APK 파일 | [`apk/ParkTrace_ParkTrace_release.apk`](apk/ParkTrace_ParkTrace_release.apk) (release 빌드) |
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (Android 14, API 34) |
| 화면 규격 | 2560 × 1440, mdpi (160 dpi) |
| Pleos SDK | Vehicle 2.3.2 · FusedLocation 1.1.3 · NaviHelper 3.0.5 · TextToSpeech 3.3.1 |

### 실행 방법

1. Pleos Connect Emulator v2.1.0을 실행합니다 (설치는 2.3장).
2. APK를 설치합니다. Pleos는 앱을 사용자 11에서 실행합니다.
   ```
   adb install -r --user 11 apk/ParkTrace_ParkTrace_release.apk
   ```
3. 앱을 실행합니다: 홈의 앱 목록에서 ParkTrace, 또는
   ```
   adb shell am start --user 11 -n com.parktrace/.ui.MainActivity
   ```
4. 화면 왼쪽 위의 상태 칩(`주행 중` / `저장된 위치`)을 누르면 앱 안 시연이 시작됩니다 (약 40초: 주차장 진입 → 경사로 → 지하 1층 통로 → 후진 주차 → 저장과 음성 안내).

소스에서 빌드하려면 JDK 17로 `gradlew assembleRelease`를 실행합니다.

### 테스트 환경과 필요한 설정

- 첫 실행 때 위치와 알림 권한을 허용합니다.
- 주차장 진입 시 앱이 스스로 앞으로 나오게 하려면 "다른 앱 위에 표시" 권한이 필요합니다.
  ```
  adb shell appops set --user 11 com.parktrace SYSTEM_ALERT_WINDOW allow
  ```
- 계정, 키, 서버는 필요 없습니다 (TextToSpeech는 ON_DEVICE 모드).
- 차량 신호를 넣어 실제 주행처럼 테스트하는 방법은 [`TESTING.md`](TESTING.md)에 있습니다 (Virtual Console, `tools/emulator-drive.ps1`).

아래는 개발 인수인계용 상세 문서입니다.

---

## 1. 현재 상태

| 항목 | 상태 |
|---|---|
| 판단 로직 (진입 감지, 경로 추적, 층 추정, 표지판 글자 해석, 투표, 주차 확정) | 작성 완료. 브라우저 시연기에서 실행해 확인함 |
| 브라우저 시연기 `web/demo.html` | 동작 확인 완료 (크롬) |
| 폰 결과 페이지 `web/index.html` | 작성 완료. 아직 배포 전 |
| 안드로이드 앱 (`app/`) | 빌드 성공, 단위 테스트 24개 통과, Pleos Connect 에뮬레이터에서 실행 확인 (2026-11-14) |
| 주차장 진입 시 자동 실행 + 진입 알림(화면·음성) | 구현, 에뮬레이터에서 확인 (9장) |
| 3D 주차 위치 (모의 주변 인식) | 구현, 에뮬레이터에서 확인 (9장) |
| 주차 요금 (요금표 입력, 무료 종료·현재 요금) | 구현. 폰 페이지 알림은 브라우저에서 실제 기기로 아직 확인 못 함 |
| SDK 호출부 이름 (클래스, 메서드, 필드) | 공식 API 레퍼런스와 대조 완료 (3장 참고) |
| 차량 카메라 인식 | **미구현. 구현하지 않기로 함** (4장 참고) |

빌드 환경 (2026-11-14): JDK 17은 `~/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2`에 있고 PATH에는 없으므로 `JAVA_HOME`을 지정해 `gradlew.bat`을 실행합니다. `gradlew`, `gradle-wrapper.jar`, `local.properties`는 만들어 두었습니다.

---

## 2. 가져와야 하는 것과 출처

### 2.1 로컬 도구 (PC에 설치)

| 필요한 것 | 버전 | 출처 |
|---|---|---|
| JDK | 17 | https://adoptium.net/ (Temurin 17) 또는 Android Studio에 포함된 JBR |
| Android Studio | 최신 | https://developer.android.com/studio |
| Android SDK Platform | API 35 (compileSdk), API 34 (에뮬레이터) | Android Studio → SDK Manager |
| Android SDK Platform-Tools (adb) | 최신 | Android Studio → SDK Manager |
| Gradle | 8.10.2 | wrapper가 내려받음 (`gradle/wrapper/gradle-wrapper.properties`) |

주의: **`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`가 저장소에 없습니다.** 아래 둘 중 하나로 만드세요.

- Android Studio로 프로젝트를 열면 자동으로 만들어집니다.
- 또는 Gradle이 설치된 상태에서 `gradle wrapper --gradle-version 8.10.2`

`local.properties`도 없습니다. Android Studio가 만들거나, 직접 `sdk.dir=<Android SDK 경로>`를 적으세요.

기준 버전 (Pleos Vehicle SDK Sample App과 동일): AGP 8.8.2, Kotlin 2.0.0, compileSdk/targetSdk 35, minSdk 28. Pleos는 minSdk 28 이상을 요구합니다.

### 2.2 Pleos Connect SDK (Maven)

저장소 주소 (이미 `settings.gradle.kts`에 등록됨):

```
https://nexus-playground.pleos.ai/repository/maven-releases/
```

| SDK | 좌표 | 이 앱에서 | 설치 문서 |
|---|---|---|---|
| Vehicle | `ai.pleos.playground:Vehicle:2.3.2` | 사용 | `/Vehicle/project-setup` |
| FusedLocation | `ai.pleos.playground:FusedLocation:1.1.3` | 사용 | `/FusedLocation/project-setup` |
| NaviHelper | `ai.pleos.playground:NaviHelper:3.0.5` | 사용 | `/NaviHelper/importing-sdk` |
| TextToSpeech | `ai.pleos.playground:TextToSpeech:3.3.1` | 사용 | `/TextToSpeech/importing-sdk` |
| SpeechToText | `ai.pleos.playground:SpeechToText:3.3.1` | **미사용** (의존성에 없음) | `/SpeechToText/importing-sdk` |

문서 경로 앞에는 `https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos`를 붙입니다. 사용 중인 4개는 이미 `app/build.gradle.kts`에 선언돼 있습니다.

참고: 시작 가이드에는 Vehicle `2.3.1`로 적혀 있고, Vehicle 연동 가이드와 Sample App 기준은 `2.3.2`입니다. `2.3.2`를 씁니다. 의존성을 못 찾으면 버전부터 확인하세요.

### 2.3 Pleos Connect Emulator (시스템 이미지)

Android Studio → Tools → SDK Manager → SDK Update Sites → `+`

| OS | 주소 |
|---|---|
| Windows / Linux | `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml` |
| Mac | `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml` |

1. SDK Platforms에서 API 34의 Pleos Connect system image 설치 후 Android Studio 재시작
2. Device Manager → Create device → **Automotive** → Pleos Connect v2.1.0
3. Advanced: Default boot = Cold, RAM 4 GB, 이미지 = API 34 Pleos Connect v2.1.0 APIs

일반 안드로이드 에뮬레이터에서는 Pleos 차량 서비스가 없어 SDK가 동작하지 않습니다. 반드시 전용 이미지를 쓰세요.

### 2.4 Pleos Connect Virtual Console (차량 신호 조작)

에뮬레이터 v2.1.0 이상에서 동작합니다.

```bash
adb forward tcp:8088 tcp:8088
# PC 브라우저: http://127.0.0.1:8088/
```

이 앱 시연에 쓰는 카드: Convenience의 Door Position(운전석 문 열기), Door Lock, Seat Occupancy / Motion의 Steering Angle, Brake Pedal.

주의: **Odometer 카드는 1 km 단위로만 바뀝니다.** 이 앱은 미터 단위 주행거리로 경로를 그리기 때문에, Virtual Console로 주차장 안 이동을 손으로 재현하기는 어렵습니다. 이 카드가 앱이 받는 트립 주행거리 값을 바꾸는지도 확인하지 못했습니다. 경로와 층 추정은 앱의 `시연 모드`로 보여 주고, Virtual Console은 문 열림 같은 실제 신호 반응을 보여 주는 데 쓰세요. **Virtual Console 카드 목록에는 위치와 속도가 없습니다.** 그래서 에뮬레이터에서는 `adb` 진입 신호(TESTING.md A1)나 `시연 모드`로 진입을 알립니다.

### 2.5 계정과 키

- **필요 없습니다.** TextToSpeech를 `Mode.ON_DEVICE`로 쓰기 때문에 앱 등록이 필요 없습니다.
- 서버 방식(`SERVER`, `HYBRID`) 음성을 쓰려면 Pleos Playground의 My Project에서 Client ID와 Client Secret을 받아 `registerApp()`을 호출해야 합니다: https://pleos.ai/playground/console/projects
- 키를 받게 되면 코드에 넣지 말고 `local.properties`에 두세요 (`.gitignore`에 이미 포함).

### 2.6 외부 API

- 지금은 **없습니다.** 서버도 없습니다. 차에서 폰으로 넘길 때는 QR 주소의 `#` 뒤에 데이터를 담습니다.
- 폰 페이지의 지도 버튼은 카카오맵 링크(`https://map.kakao.com/link/map/이름,위도,경도`)만 엽니다. 키가 필요 없습니다.
- 선택 과제: 주차장 좌표를 미리 넣으려면 공공데이터포털(https://www.data.go.kr)의 주차장 정보 API를 쓸 수 있습니다. 어떤 API를 쓸지는 아직 정하지 않았습니다.

### 2.7 문서 주소 모음

기준 주소: `https://document.pleos.ai`

| 내용 | 경로 |
|---|---|
| SDK 개요 | `/docs/pleos-only/connect-sdk-pleos/` |
| 빠른 시작 | `/docs/pleos-only/getting-started` |
| 에뮬레이터 설치 | `/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator` |
| Virtual Console 셋업 | `/docs/connect/guide/getting-started/application-development/use-vehicle-emulator` |
| Virtual Console 카드 목록 | `/docs/pleos-only/app-test-guide/virtual-console-guide` |
| Vehicle SDK Sample App | `/docs/pleos-only/tutorial/vehicle-sdk-sample-app` |
| 주행 중 화면 제한 구현 | `/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide` |
| Vehicle 권한 | `/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission` |
| Vehicle 지원 여부 확인 | `/docs/pleos-only/connect-sdk-pleos/Vehicle/check-capability` |
| Vehicle 오류 코드 | `/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-error-code` |
| FusedLocation 위치 데이터 | `/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access` |
| NaviHelper 목적지 | `/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations` |
| NaviHelper 이벤트 | `/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners` |
| TextToSpeech 앱 등록 | `/docs/pleos-only/connect-sdk-pleos/TextToSpeech/app-registration` |
| 각 SDK의 API 레퍼런스 | `/docs/pleos-only/connect-sdk-pleos/<SDK이름>/entry` |

API 레퍼런스 개별 페이지 주소 규칙 (예시는 실제로 열어 확인한 주소):

```
/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/register-door-position
/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.listener/seat-occupancy-listener/entry
/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/entry
/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/destination-info/entry
/docs/pleos-only/connect-sdk-pleos/TextToSpeech/text-to-speech/ai.pleos.playground.tts/text-to-speech/entry
```

주의: API 레퍼런스 페이지는 단순 텍스트 추출(curl, 일반 fetch)로는 본문이 비어 보입니다. 브라우저로 열어서 확인하세요.

---

## 3. API별 사용 내역

아래 이름과 인자는 2026-11-14에 공식 API 레퍼런스를 열어 하나씩 대조했습니다. 빌드로 확인한 것은 아닙니다.

### 3.1 Vehicle SDK → `app/src/main/java/com/parktrace/sdk/VehicleSource.kt`

진입점: `ai.pleos.playground.vehicle.Vehicle(context)` → `initialize()` → `getOdometer()` 등 → 끝나면 `release()`

| 용도 | 호출 | 리스너 콜백 | 권한 |
|---|---|---|---|
| 주행거리 (m) | `getOdometer().registerTripCurrentDrivingDistance(listener)` | `DistanceDrivenListener.onDistanceDrivenUpdated(value: Float?)` | `CAR_DRIVE` |
| 핸들 각도 (앞바퀴 각도, 왼쪽이 음수) | `getSteeringWheel().registerSteeringWheelAngle(listener)` | `SteeringWheelAngleListener.onSteeringWheelAngleUpdated(angle: Float?)` | `CAR_DRIVE` |
| 브레이크 페달 | `getBrake().registerBrakePedalStatus(listener)` | `BrakePedalStatusListener.onBrakePedalStatusUpdated(status: Boolean?)` | `CAR_DRIVE` |
| 운전석 문 열림 | `getDoor().registerDoorPosition(area: DoorArea, listener)` | `DoorPositionListener.onPositionUpdated(pos: Int?)` | `CAR_DOORS` |
| 문 잠금 | `getDoor().registerDoorLockState(area: DoorArea, listener)` | `DoorLockStateListener.onDoorLockStateUpdated(state: Boolean?)` | `CAR_DOORS` |
| 운전석 착석 | `getSeat().registerSeatOccupancy(area: SeatArea, listener)` | `SeatOccupancyListener.onSeatOccupancyUpdated(occupancy: Boolean?)` | `CAR_SEATS` |

- 권한 이름 앞에는 `pleos.car.permission.`이 붙습니다. 셋 다 보호 수준이 normal입니다.
- 해제는 `unregister...`에 등록할 때와 같은 인자를 넘깁니다. 문과 좌석은 `(area, listener)`, 주행거리와 핸들은 `(listener)`입니다.
- 모든 리스너는 `VehicleListener`를 상속하고 `onFailed(e: Exception)`를 가집니다.
- 영역 값: `DoorArea(DoorValue.ROW_1_LEFT)`, `SeatArea(SeatValue.ROW_1_LEFT)`. **`SeatValue.ROW_1_LEFT`는 문서에서 직접 확인하지 못했습니다.** `DoorValue`와 같은 이름일 것으로 보고 썼습니다. 패키지는 `ai.pleos.playground.vehicle.constant.area`입니다.
- 차량 통신은 비동기입니다. 값은 콜백으로만 옵니다.
- 실제 차종이 기능을 지원하는지는 `check...Capabilities`로 확인해야 합니다. 지금 코드는 이 확인을 하지 않고, 실패하면 최대 3번 다시 등록합니다.

Vehicle SDK에 **없는 것**: 기어 위치, 차량 속도. 그래서 "8초 동안 주행거리가 그대로면 정차"로 판단합니다.

### 3.2 FusedLocation SDK → `sdk/LocationSource.kt`

진입점: `ai.pleos.playground.location.fused.FusedLocation(context)`

| 용도 | 호출 | 비고 |
|---|---|---|
| 지원 여부 | `isSupported(): Boolean` | false면 위치 기능을 끕니다 |
| 초기화, 해제 | `initialize()`, `release()` | |
| 위치 수신 | `registerFusedLocationCallback(listener: FusedLocationListener)` | `onChanged(location: LocationData?)`, `onError(exception: Exception)` |
| 수신 중지 | `unregisterFusedLocationCallback(listener)` | |
| 주차장 진입 알림 등록 | `addProximityAlert(request: ProximityAlertRequest, listener: ProximityAlertListener)` | `onRegistered(alertId: String)`, `onTransition(event: ProximityAlertEvent)`, `onError(exception)` |
| 알림 해제 | `removeProximityAlert(alertId: String)` | |

데이터 형태 (패키지 `ai.pleos.playground.location.fused.model`):

```kotlin
data class LocationData(val latitude: Double, val longitude: Double, val altitude: Double,
                        val heading: Float, val confidence: Float, val timestampMillis: Long)
data class ProximityAlertRequest(val latitude: Double, val longitude: Double,
                                 val radiusMeters: Float, val transitionTypes: Set<ProximityTransition>)
data class ProximityAlertEvent(val alertId: String, val transition: ProximityTransition)
```

- 권한: `pleos.car.permission.FUSED_LOCATION` (dangerous, 실행 중에 요청해야 함)
- **`confidence`의 단위와 범위는 문서에 없습니다.** `ParkingDetector.Config`의 기준값 0.3과 0.6은 추측입니다. 에뮬레이터나 실제 차에서 값을 찍어 보고 맞춰야 합니다.
- 차량 설정으로 위치 접근이 막혀 있으면 `showLocationAccessBlockedPopup()`으로 안내할 수 있습니다. 지금은 쓰지 않습니다.

### 3.3 NaviHelper SDK → `sdk/NaviSource.kt`

진입점: `ai.pleos.playground.navi.helper.NaviHelper(context)`

| 용도 | 호출 | 비고 |
|---|---|---|
| 초기화 | `initialize(onConnected: () -> Unit, onFailed: (Throwable) -> Unit)` | 연결된 뒤에 리스너를 등록 |
| 리스너 | `addListener(listener: NaviHelperEventListener)`, `removeListener(listener)` | 리스너 메서드는 모두 기본 구현이 있어 필요한 것만 재정의 |
| 목적지 요청 | `getDestinationInfo()` | 결과는 `onDestinationInfo(destinationInfo: DestinationInfo)`로 옴 |
| 현재 위치 요청 | `getCurrentLocationInfo()` | 결과는 `onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo)`로 옴 |
| 오류 | `onError(errorCode: NaviErrorCode)` | |
| 해제 | `release()` | |

- 쓰는 필드: `DestinationInfo.name`, `DestinationInfo.address`, `CurrentLocationInfo.address` (모두 `String`)
- `DestinationInfo`에는 `tollFare`(원), `remainDistance`(m), `remainTime`(초)도 있습니다. 지금은 쓰지 않습니다.
- 권한: `pleos.car.permission.NAVI_ROUTE` (`get*Info()` 계열)

### 3.4 TextToSpeech SDK → `sdk/Announcer.kt`

진입점: `ai.pleos.playground.tts.TextToSpeech(context, mode = Mode.ON_DEVICE)` (기본값은 `Mode.HYBRID`)

| 용도 | 호출 |
|---|---|
| 초기화 | `initialize(listener: OnInitializeListener)` → `onSuccess()`, `onFailure(errorType: ErrorType)` |
| 언어 | `setLocale(locale: Locale)` |
| 말하기 | `speak(text: String, isFinal: Boolean = true)` |
| 중지, 해제 | `stop()`, `release()` |

- 권한: `pleos.car.permission.TTS_SERVICE`
- 다른 소리와 겹칠 때의 규칙(오디오 포커스)은 아직 처리하지 않았습니다. 문서: `/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development`

### 3.5 SpeechToText SDK (미사용)

쓰지 않습니다. 넣으려면 의존성 `ai.pleos.playground:SpeechToText:3.3.1`, 권한 `pleos.car.permission.STT_SERVICE`가 필요합니다. `ON_DEVICE` 모드는 앱 등록이 필요 없습니다. 문서: `/docs/pleos-only/connect-sdk-pleos/SpeechToText/` 아래 `importing-sdk`, `initialization-and-release`, `start-and-stop`, `result-listeners`. 클래스와 메서드 이름은 이번에 대조하지 않았습니다.

### 3.6 선언한 권한 전체 (`AndroidManifest.xml`)

```
pleos.car.permission.CAR_DOORS
pleos.car.permission.CAR_DRIVE
pleos.car.permission.CAR_SEATS
pleos.car.permission.FUSED_LOCATION
pleos.car.permission.NAVI_ROUTE
pleos.car.permission.TTS_SERVICE
android.permission.FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION
android.permission.ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, POST_NOTIFICATIONS
```

---

## 4. 쓰면 안 되는 것 (공모전 규칙)

공모전 공지 "개발 범위 및 기술 문의 안내"와 운영사무국 답변 메일(2026-11-13)에 따른 제한입니다.

| 금지 | 이유 |
|---|---|
| 차량 카메라 API (`PleosCameraManager` 등), Android 카메라 권한 | 공개된 SDK 5종(Vehicle, NaviHelper, TextToSpeech, SpeechToText, FusedLocation)만 사용 가능. 카메라는 문서에 공개된 범위가 아님 |
| Pleos LLM SDK, 공개 문서에 없는 SDK | 공모전 기간 중 제공되지 않음 |
| 차량 마이크 소리를 외부 음성 서비스로 보내기 | 음성 인식은 SpeechToText SDK만 사용 |
| 표준 AAOS 차량 API로 기어 읽기 (`android.car`) | 공개 SDK 5종 밖. 이전 버전에 있었으나 제거함 |

운영사무국 답변 요지: 카메라 인식은 기획서에 **향후 계획**으로 적고, 공개 SDK로 구현한 기능과 인식 결과를 가정한 데모 화면으로 시연해도 된다. 모의 데이터나 대체 시연도 심사 인정 범위에 들어간다.

그래서 카메라 자리는 이렇게 되어 있습니다.

- `sdk/SignReader.kt`: "표지판 글자를 읽어 주는 것"의 인터페이스
- `sdk/SimulatedSignReader.kt`: 카메라를 열지 않고, 버튼이나 시나리오가 넣어 준 글자를 그대로 전달
- 카메라가 열리면 `SignReader`를 구현한 클래스 하나만 추가하면 됩니다. 그 전에는 카메라 코드를 넣지 마세요.

---

## 5. 이어서 할 작업 (순서대로)

1. **도구 확인**: `java -version`이 17인지, `adb version`이 되는지 확인합니다. 없으면 2.1을 설치합니다.
2. **Gradle wrapper 만들기**: 2.1의 방법 중 하나로 `gradlew`를 만듭니다.
3. **단위 테스트**: `./gradlew test`. `app/src/test`의 5개 파일이 통과해야 합니다. 같은 기대값을 브라우저 시연기에서는 이미 확인했습니다.
4. **빌드**: `./gradlew assembleDebug`. 컴파일 오류가 나면 3장의 표와 API 레퍼런스를 보고 고칩니다. 가장 의심되는 곳은 `SeatValue.ROW_1_LEFT`입니다.
5. **에뮬레이터 실행**: 2.3대로 만들고 `./gradlew installDebug`. 첫 실행 때 위치와 알림 권한을 허용합니다.
6. **앱 시연 모드 확인**: 화면 왼쪽 위 상태 칩(`주행 중`/`저장된 위치`) 을 눌러 시연 모드를 시작하고(화면에 버튼은 없음) `지하 2층 · C-15`로 저장되는지 봅니다. 음성이 나오는지도 확인합니다.
7. **Virtual Console 확인**: `adb` 진입 신호(TESTING.md A1) → 모의 표지판 신호(B2, C-15)를 `adb`로 보내기(TESTING.md A2) → 2초 뒤 Virtual Console의 Door Position에서 운전석 문 열기. `지하 2층 · C-15`로 저장되고 음성이 나오는지 봅니다. Steering Angle을 바꿨을 때 앱에 값이 들어오는지, Odometer 카드가 트립 주행거리를 바꾸는지도 로그로 확인합니다.
8. **`confidence` 값 확인**: `LocationSource`에 로그를 넣어 실제 값을 보고 `ParkingDetector.Config`를 조정합니다.
9. **폰 페이지 배포**: `web/` 폴더를 GitHub Pages로 올리고 `app/build.gradle.kts`의 `FIND_PAGE_URL`을 그 주소로 바꿉니다. 지금은 `https://YOUR_GITHUB_ID.github.io/parktrace/`로 비어 있습니다.
10. **제출물 정리**: 이 문서 6장에 에뮬레이터 화면 해상도를 적고, 시연 영상을 녹화하고, 서명한 release APK를 `apk/`에 넣습니다.

제출 관련 메모 (공지 기준, 제출 전에 다시 확인할 것): GitHub 저장소는 11월 16일까지 공개로 바꾸고 11월 23일까지 유지해야 합니다. 심사는 제출 메일에 적은 마지막 커밋 기준입니다.

---

## 6. 시연 방법

### A. 브라우저 시연기 (설치 없이 바로)

`web/demo.html`을 크롬에서 더블클릭해 엽니다. 앱의 판단 로직을 자바스크립트로 그대로 옮긴 페이지입니다.

- `▶ 자동 재생`: 도로 → 공영주차장 입구(GPS 약화, 차단기 정차) → 직선 경사로로 B1 → U턴 → 경사로로 B2 → 우회전해 통로 → 우회전 → 주차 → 문 열림. 약 40초.
- 직접 조작: `GPS 약함`을 켜고 `5 m 이동`을 3번 누르면 주차장 진입으로 판단합니다. `내리막`과 `우`를 켜고 13번 이동하면 2바퀴(지하 2층)입니다. 모의 표지판 버튼을 누른 뒤 `운전석 문 열기`를 누르면 저장됩니다.
- 일부러 넣은 오독(`CI5`)도 `C-15`로 바로잡습니다.

확인한 결과 (2026-11-14, 크롬):

- 자동 재생 결과: `지하 2층 · C-15`, "추정 B2와 일치", 음성 문장 "지하 2층 C 15 기둥에 주차했어요."
- 경로 안내: 입구에서 직진 → 우회전(U턴) → 우회전 → 직진 → 주차 (등록된 주차장은 지오펜스가 입구 80m 전에 켜지므로 안내와 3D 차단기는 80m 지점부터)
- 표지판 글자 해석 17개, 층 추정 5개, 경로 안내 3개, 투표 2개 예시가 `app/src/test`의 기대값과 모두 일치

로직을 고치면 `app/src/main/java/com/parktrace/core`와 `web/demo.html` 양쪽을 같이 고쳐야 합니다.

### B. Pleos Connect 에뮬레이터 (앱 빌드 후)

- 앱의 시연 모드(화면 왼쪽 위 상태 칩(`주행 중`/`저장된 위치`) 을 누름): 위와 같은 시나리오가 앱 안에서 재생됩니다.
- 직접 조작: 5장의 7번 순서대로 합니다.

테스트 환경 (제출 전에 실제 값으로 채울 것):

- Pleos Connect Emulator v2.1.0 (Android 14, API 34), Pleos Connect Virtual Console
- Vehicle SDK 2.3.2 / FusedLocation 1.1.3 / NaviHelper 3.0.5 / TextToSpeech 3.3.1
- 화면 규격: 2560 × 1440 (mdpi, 160 dpi)

### 폰 전달

(지금은 앱 화면에 `폰으로 보내기` 버튼이 없습니다. 시연을 위해 뺐고 코드는 `MainActivity.showQr`에 남아 있습니다.) `폰으로 보내기` → QR을 폰으로 찍으면 `web/index.html`이 층, 기둥, 경로, 입구 지도 링크를 보여줍니다. 데이터는 주소의 `#d=` 뒤에만 있어 서버로 전송되지 않습니다. 형식은 `data/ShareLink.kt`에 있습니다.

---

## 7. 프로젝트 구조

```
app/src/main/java/com/parktrace/
  core/      안드로이드와 무관한 순수 로직. 단위 테스트 대상
             ParkingDetector(상태 판단) · DeadReckoner(경로 추적) · FloorEstimator(층 추정)
             SignTextParser(표지 글자 해석) · SpotVoter(여러 번 본 표지 중 고르기) · Directions(경로 안내 문장) · Signals(신호 형식)
  sdk/       Pleos SDK를 감싼 부분. SDK 이름이 틀리면 여기만 고치면 됨
             VehicleSource · LocationSource · NaviSource · Announcer(음성) · SignReader / SimulatedSignReader(카메라 자리)
  service/   ParkingMonitorService(전체 연결, 포그라운드 서비스) · ParkTraceState(화면과 공유하는 상태)
  data/      ParkingRecord · ParkingRepository · GarageRegistry(주차장 학습) · ShareLink(QR 주소)
  ui/        MainActivity · TraceView(경로 그림) · QrCode
  demo/      DemoScenario(앱 안 시연 시나리오)
app/src/test/   SignTextParserTest · SpotVoterTest · FloorEstimatorTest · DirectionsTest · ParkingDetectorTest
web/index.html  폰에서 여는 결과 페이지
web/demo.html   브라우저 시연기
apk/            제출용 APK를 넣을 자리
```

신호 흐름:

```
VehicleSource ─┐
LocationSource ┴─► ParkingMonitorService ─► ParkingDetector ─► 주차장 진입 ─► SimulatedSignReader 켜짐 ─► SignTextParser ─► SpotVoter
                                                             └► 주차 확정 ─► ParkingRecord 저장 ─► Announcer(음성) ─► 화면, QR
```

---

## 8. 알려진 한계

1. **실제 차량에서는 실행해 보지 못했습니다.** 빌드와 에뮬레이터 실행은 확인했습니다. 에뮬레이터는 주행거리(`ERROR_NOT_SUPPORTED_PROPERTY`)를 지원하지 않습니다.
2. **카메라 인식은 향후 계획입니다.** 실제 차에서는 층 추정과 경로 기록만 동작하고 기둥 번호는 비어 있습니다.
3. **위치 신뢰도 기준값은 추측입니다** (3.2 참고).
4. **기어와 속도를 읽을 수 없습니다.** 8초 동안 움직이지 않으면 정차로 판단합니다.
5. **차종별 지원 여부를 확인하지 않습니다.** `check...Capabilities`를 넣어야 합니다.
6. **주행 중 화면 제한을 적용하지 않았습니다.** `MainActivity`에 `distractionOptimized` 표시만 있습니다. 주행 중에는 버튼을 숨기는 처리가 필요합니다.
7. **시동을 끄면 앱이 멈출 수 있습니다.** 문서상 시동이 꺼지면 저전력 상태로 들어가 대부분의 기능이 중단됩니다. 그래서 주차 확정과 저장은 문이 열리는 순간에 끝나도록 되어 있습니다. 실제 동작은 확인하지 못했습니다.
8. **에뮬레이터에서 위치를 바꾸는 방법을 찾지 못했습니다.** 자동 진입 감지는 지금은 브라우저 시연기와 앱의 시연 모드에서만 볼 수 있습니다.

---

## 9. 2026-11-14 추가 기능

### 9.1 주차장 진입 → 앱 자동 실행 + 진입 알림

- 진입을 판단하면 (`ParkingMonitorService.onGarageEntered`)
  1. 앱을 앞으로 띄웁니다 (`bringAppToFront`).
  2. 음성으로 "주차장에 진입했어요. 30분 동안 무료예요."라고 말합니다. 앱 화면에 진입 시각과 무료 종료 시각이 나옵니다.
  3. 알림은 앱이 스스로 앞으로 나올 수 없을 때만 띄웁니다 (`notifyEntry`, 채널 `parktrace_entry`). Pleos는 중요도와 상관없이 모든 알림을 화면 위 팝업으로 띄워서 앱 화면을 가리기 때문입니다.
- 안드로이드는 백그라운드에서 화면을 띄우는 것을 막습니다. 그래서 두 가지를 같이 씁니다.
  - "다른 앱 위에 표시" 권한(`SYSTEM_ALERT_WINDOW`)이 있으면 바로 띄웁니다.
  - 없으면 전체 화면 알림(`USE_FULL_SCREEN_INTENT`)으로 엽니다.
- 에뮬레이터에서는 이렇게 권한을 줍니다. Pleos는 앱을 **사용자 11**에서 실행합니다.
  ```
  adb shell appops set --user 11 com.parktrace SYSTEM_ALERT_WINDOW allow
  ```
- Pleos 홈은 앱을 패널 안에 넣어 보여줍니다. 그래서 가려진 앱도 `onStop`이 오지 않습니다. 앱이 화면 맨 앞에 있는지는 `onTopResumedActivityChanged`로 판단합니다.
- 테스트와 시연 영상용 신호(adb에서만 보낼 수 있음, DUMP 권한으로 보호):
  ```
  adb shell am broadcast -a com.parktrace.action.SIM_GARAGE_ENTRY -n com.parktrace/.service.DebugEntryReceiver
  ```
- 수동 진입(버튼, 위 신호)은 이전 주차가 끝난 상태(PARKED)에서도 받습니다. 에뮬레이터에는 주행거리 신호가 없어서 다시 "주행 중"으로 돌아오지 못하기 때문입니다. GPS나 지오펜스로 자동 진입할 때는 기존처럼 DRIVING일 때만 받습니다.
- Pleos SDK 호출(초기화, 등록, 음성)은 전용 스레드(`ParkTrace-sdk`)에서 합니다. 메인 스레드에서 할 때는 에뮬레이터에서 ANR(5초 응답 없음)이 났습니다.

### 9.2 3D 주차 위치 (`ui/SceneView.kt`)

- **경로와 내 차 위치는 실제 데이터입니다.** 핸들 각도와 주행거리로 계산합니다.
- **주변 차량, 칸, 기둥은 모의 인식입니다** (`sdk/SurroundVision.kt`의 `SimulatedSurroundVision`). 카메라 API는 공모전 규칙상 쓸 수 없습니다 (4장). 운영사무국이 인정한 "인식 결과를 가정한 데모"입니다. 카메라가 열리면 `SurroundVision`을 구현한 클래스 하나만 추가하면 됩니다.
- 인식된 주변 차는 테슬라 주행 화면처럼 그립니다. 연한 회색 한 가지로 칠하고(무광, 라이트·트림 색 없음), 차종은 세단·SUV·밴 실루엣만 씁니다. 차마다 바닥에 부드러운 그림자를 깔고, 멀어질수록 배경색으로 흐려집니다. 내 차만 디테일과 파란색을 유지합니다. 차 모델은 Kenney Car Kit(CC0)입니다. Pleos 홈의 차량 3D 모델은 시스템 앱 리소스입니다. SDK 라이선스 3.4항(복제, 역설계 금지)과 차량 디자인 권리 때문에 가져다 쓰지 않습니다.
- OpenGL 없이 Canvas로 원근 투영합니다. 상태가 바뀌거나 드래그할 때만 다시 그립니다. 가로로 드래그하면 회전, 세로로 드래그하면 기울기가 바뀝니다.
- **주차장 환경을 주행 중에도 실시간으로 그립니다** (모의 인식). 입구에 차단기와 정산소가 서고, 램프 구간에는 양옆 벽(안전 도색 포함)이 경로를 따라 이어집니다. 회전 뒤 8m 이상 직진하면 그 구간을 통로로 보고 아스팔트 바닥, 노란 중앙선, 진행 방향 화살표를 깝니다. 양옆에는 주차 칸, 주차된 차, 기둥이 생깁니다. 주차장마다 같은 배치가 나오도록 시드를 고정합니다.
- 정차하면 통로에 맞춰 칸 격자를 다시 정렬합니다. 내 차와 가장 가까운 칸이 내 칸이 되고, 차는 그 칸에 맞춰 놓입니다 (앞으로 넣었는지 후진으로 넣었는지 유지). 같은 쪽 기둥에는 읽은 표지를 기준으로 번호(C-14, C-15, C-16)를 붙입니다. 통로를 찾지 못하면 예전처럼 차 주변에만 칸을 만듭니다.
- 등록된 주차장에서는 GPS로 입구 100m 앞부터 진입을 판단합니다. 그래서 회전하기 전 직진 구간은 "주차장 진입로"로 그립니다. 진입한 지점에 차단기가 서고, 양옆 벽과 바닥이 이어집니다. 첫 회전이 나오면 차단기는 램프 6m 앞으로 옮겨집니다.

### 9.3 주차 요금과 폰 알림

- 요금표는 주차장마다 직접 입력합니다 (`data/FeeBook.kt`. 입력 화면 `MainActivity.showFeeDialog`는 남아 있지만 시연을 위해 `요금표` 버튼을 화면에서 뺐습니다). 기본값은 "최초 30분 무료, 이후 10분당 1,000원"입니다. 계산은 `core/FeePolicy.kt`에 있고, 시작한 단위마다 과금하며 1일 최대 금액을 적용합니다. 테스트는 `FeePolicyTest`입니다.
- QR 주소에 진입 시각(`e`)과 요금표(`fp`)를 담습니다. 폰 페이지(`web/index.html`)는 아래를 보여줍니다.
  - 현재 예상 요금, 진입 시각, 무료 종료 시각, 다음 요금 인상 시각 (15초마다 갱신)
  - **요금 알림 켜기**: 브라우저 알림입니다(`web/sw.js` 필요). 무료 종료 10분 전, 무료 종료, 이후 1시간마다 알립니다. **페이지를 열어 둔 동안만** 동작합니다. 서버가 없어서 푸시를 보낼 수 없습니다.
  - **캘린더에 무료 종료 알림 추가**: `.ics` 파일을 받아 폰 캘린더에 넣으면 브라우저를 닫아도 10분 전과 종료 시각에 알림이 옵니다.
- 요금 계산 공식은 Kotlin과 JavaScript 두 곳에 있습니다. 바꿀 때는 둘 다 고치세요. 같은 기대값이 나오는 것을 Node로 확인했습니다.
- **현대차 원격 앱(블루링크/마이현대)으로 보내는 알림은 구현할 수 없습니다.** 외부 앱이 알림을 넣는 공개 API가 없고, 공모전에서 허용한 SDK 5종에도 들어 있지 않습니다. 기획서에는 향후 계획으로 적습니다.
- 진입 시점에 폰으로 바로 알릴 방법은 없습니다 (서버 없음). 폰 연결은 주차 후 QR을 찍을 때 이루어집니다.

### 9.4 실제 주행처럼 테스트하기

- 절차서: [`TESTING.md`](TESTING.md). Virtual Console 가이드 방식의 카드 수동 테스트와 자동 주행 스크립트를 함께 정리했습니다.
- 자동 주행: `powershell -ExecutionPolicy Bypass -File tools\emulator-drive.ps1`
  - 신호 출처: GPS는 `adb emu geo fix`, 속도·기어·핸들·주행거리·문은 Virtual Console HTTP API, 표지판은 SIM_SIGN 테스트 신호
  - 진입은 앱이 실제 신호로 판단합니다. 첫 실행은 고도 급강하, 두 번째부터는 학습한 주차장 근접 알림입니다.
- 에뮬레이터는 트립 주행거리를 지원하지 않습니다. 그래서 `VehicleSource`가 누적 주행거리(`registerDistanceDriven`, km)로 대신 계산합니다. 실제 차는 트립 주행거리를 씁니다.
- Virtual Console의 Steering Angle은 앱에 부호가 반대로 들어옵니다 (+30 → −30, 왼쪽).
- 3D 화면의 주변 차량에는 신뢰도(%)와 인식 테두리를 표시하지 않습니다.
- **기어 API를 씁니다.** Vehicle SDK에 `getGear().registerGearSelection`이 있습니다 (3.1장의 "기어 없음"은 틀렸음). P가 되면 3초 뒤 정차를 확정하고, R에서는 경로를 뒤로 계산해 후진 주차를 기록합니다 (`ReverseParkingTest`). 에뮬레이터에서 기어 신호가 들어오는 것을 확인했습니다. 필요한 권한은 문서에 따로 적혀 있지 않습니다.
- **Pleos 주차 기능과 함께 씀:** R을 넣으면 Pleos가 후방·좌우 카메라(Android EVS)를 띄웁니다. 주차하는 동안은 그 화면을 쓰고, P가 되면 ParkTrace가 다시 앞으로 나와 위치를 보여줍니다. EVS 카메라 영상과 자동주차 미니앱은 `signature|privileged` 권한이 필요해서 앱이 직접 쓸 수 없습니다 (에뮬레이터에서 확인).
- **Android 기본 요소:** 화면 아이콘은 Android 표준 Material Icons(Google, Apache 2.0)를 벡터로 넣었습니다 (`res/drawable/ic_md_*.xml`). Android Automotive와 Android Auto에는 자동차 3D 객체 같은 기본 그래픽이 없습니다. 이 Pleos 이미지에 Car App Library 템플릿 호스트(`com.android.car.templates.host`)가 있지만, 템플릿도 지도 영역은 앱이 직접 그려야 해서 쓰지 않았습니다.
- **3D 차량 모델:** Kenney Car Kit 3.1 (CC0, https://kenney.nl/assets/car-kit). `tools/convert_car_models.py`로 변환해 `app/src/main/assets/cars/`에 넣었습니다. 차체 색은 앱에서 다시 칠합니다. Pleos 주행 화면과 같은 스타일(밝은 바닥, 흰색 차량)로 맞췄고, 내 차만 파란색입니다. Pleos 주행 화면의 차량 3D 리소스는 시스템 앱 안에 있고 라이선스 3.4항(복제·역설계 금지) 때문에 가져다 쓰지 않습니다. 모델 파일이 없으면 코드로 만든 기본 모델로 그립니다.
- **시연 모드의 후방 카메라 화면:** 앱 안 시연에서 후진할 때는 실제 카메라 대신 주차장 사진 한 장을 후방 카메라 영상처럼 보여 주고, 그 위에 후진 가이드선을 그립니다 (`ui/RearCameraView.kt`). 카메라는 열지 않습니다. 화면에 "시연용 가상 화면"이라고 표시합니다. 사진: "Parkhaus Rheinauhafen" (H005, 퍼블릭 도메인, https://commons.wikimedia.org/wiki/File:Parkhaus_Rheinauhafen.jpg), `res/drawable-nodpi/rear_camera_garage.jpg`.

### 9.5 남은 일

- `web/demo.html`(브라우저 시연기)에는 요금과 3D가 아직 없습니다.
- 폰 페이지의 알림과 캘린더 기능을 실제 iPhone과 Android에서 확인해야 합니다. 서비스 워커가 있어야 하므로 GitHub Pages(https)에 올린 뒤에 확인합니다.
- `FIND_PAGE_URL`은 아직 비어 있습니다 (5장 9번).
- FusedLocation `confidence`가 에뮬레이터에서 5.0으로 나옵니다. `ParkingDetector.Config`의 기준값(0.3, 0.6)과 단위가 다를 가능성이 큽니다 (5장 8번).
