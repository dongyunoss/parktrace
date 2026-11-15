# ParkTrace 테스트 절차 (Pleos Connect Emulator + Virtual Console)

Pleos 공식 가이드 [Virtual Console 테스트 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)의 방식으로 테스트합니다. **Emulation Controls에서 카드를 골라 값을 바꾸고, 에뮬레이터와 앱에서 결과를 확인**하는 방식입니다. 실제 주행처럼 이어지는 시연은 B장의 스크립트로 합니다.

확인한 날짜: 2026-11-14, Pleos Connect Emulator v2.1.0 (API 34), 화면 2560 × 1440.

---

## 0. 준비

1. 에뮬레이터를 켜고 앱을 설치합니다 (README 5장).
2. 권한을 줍니다. Pleos는 앱을 **사용자 11**에서 실행하므로 `--user 11`이 꼭 필요합니다.
   ```
   adb shell pm grant --user 11 com.parktrace pleos.car.permission.FUSED_LOCATION
   adb shell pm grant --user 11 com.parktrace android.permission.ACCESS_FINE_LOCATION
   adb shell pm grant --user 11 com.parktrace android.permission.POST_NOTIFICATIONS
   adb shell appops set --user 11 com.parktrace SYSTEM_ALERT_WINDOW allow   # 진입 시 자동 실행
   ```
3. Virtual Console에 연결합니다.
   ```
   adb forward tcp:8088 tcp:8088
   ```
   PC 브라우저에서 http://127.0.0.1:8088/ 을 열고 **Emulation Controls**를 엽니다.
4. 처음 상태를 맞춥니다.
   - Convenience → Door Position: 모든 문 Close
   - Convenience → Trunk / Frunk Position: Close
   - Motion → Gear Selection: D

## 1. 앱이 쓰는 카드

| 도메인 → 카드 | 앱에 들어오는 신호 | 앱이 하는 일 |
|---|---|---|
| Motion → Odometer | 누적 주행거리 (km) | 이동 거리. 에뮬레이터는 트립 주행거리를 지원하지 않아서(`ERROR_NOT_SUPPORTED_PROPERTY`) 앱이 누적 주행거리로 대신 계산합니다 |
| Motion → Steering Angle | 핸들 각도 | 주차장 안 경로 계산. **부호 주의:** 카드의 +30은 앱에 −30(왼쪽)으로 들어갑니다. 오른쪽으로 돌려면 음수를 넣으세요 |
| Motion → Brake Pedal | 브레이크 밟음 | 기록만 함 |
| Motion → Gear Selection | 기어 (Vehicle SDK `getGear()`) | **P: 3초 뒤 정차 확정.** **R: 경로를 뒤로 계산(후진 주차)**, 이때 Pleos가 후방·좌우 카메라를 띄움. P가 되면 앱이 다시 앞으로 나옴. 기어가 D인 동안에는 멈춰 있어도 정차로 보지 않음 |
| Convenience → Door Position (앞 왼쪽) | 운전석 문 열림 | 정차 상태면 **주차 확정 → 저장 + 음성** |
| Convenience → Door Lock → Lock | 문 잠김 | 주차 확정 (문 열림과 같음) |
| Convenience → Seat Occupancy (Driver) → Empty | 운전석 비움 | 주차 확정 (문 열림과 같음) |
| (카드 없음) 위치 | FusedLocation | 진입 판단(고도 급강하, GPS 정체, 등록된 주차장). 에뮬레이터 창의 `…` → **Location** 또는 `adb emu geo fix <경도> <위도> <고도>`로 바꿉니다 |

가이드의 카드 목록에는 위치와 속도가 없습니다. Odometer 카드는 **1km 단위**라서 주차장 안의 짧은 경로(수 m)는 카드로 만들 수 없습니다. 경로와 층 추정은 B장의 스크립트로 확인하세요. 스크립트는 같은 API로 소수점 km를 보냅니다.

---

## A. 카드로 하는 수동 테스트

| # | 할 일 | 확인할 것 |
|---|---|---|
| A1 | 앱을 열고 하단 내비게이션 아이콘을 눌러 앱을 가립니다. 그다음 진입 신호를 보냅니다: `adb shell am broadcast -a com.parktrace.action.SIM_GARAGE_ENTRY -n com.parktrace/.service.DebugEntryReceiver` | 앱이 저절로 앞으로 나옴. 상단에 "주차장에 진입했어요 · 진입 시각 · 무료 ~까지" 알림. 음성 "주차장에 진입했어요. 30분 동안 무료예요." 왼쪽 카드에 "진입 HH:MM · 무료 HH:MM까지 (30분 남음)" |
| A2 | 모의 표지판 신호를 보냅니다 (앱 화면에는 버튼이 없음): `adb shell "am broadcast -a com.parktrace.action.SIM_SIGN --es text 'B2' -n com.parktrace/.service.DebugEntryReceiver"`, 이어서 `text 'C-15'` | 큰 글자가 `B2 · C-15`로 바뀜 |
| A3 | Motion → Gear Selection → **P** 후 3초 기다립니다 | "정차 · 주차 확인 중". 3D 화면에 주변 칸, 차량, 기둥(C-14, **C-15**, C-16)이 나타남 |
| A4 | Convenience → Door Position → 앞 왼쪽 **Open** | "주차 완료", "저장 완료: B2 · C-15", 음성 "지하 2층 C 15 기둥에 주차했어요." |
| A5 | A1부터 다시 하고, A4 대신 Door Lock → **Lock** 또는 Seat Occupancy (Driver) → **Empty** | A4와 같은 결과 |
| A6 | (지금은 할 수 없음: `요금표` 버튼을 화면에서 뺌) 앱의 `요금표` → 프리셋 "1시간 무료 · 10분 500원" → 저장 | 메시지 "요금표 저장: 최초 1시간 무료 · 이후 10분당 500원 · 1일 최대 20,000원". 요금 줄의 무료 종료 시각이 바뀜 |
| A7 | (지금은 할 수 없음: `폰으로` 버튼을 화면에서 뺌) `폰으로 보내기` | QR 표시. 폰으로 찍으면 층·기둥·요금 카드가 보임 (폰 페이지를 배포한 뒤에 가능, README 5장 9번) |
| A8 | 3D 화면을 가로/세로로 드래그합니다 | 회전, 기울기 변경 |
| A9b | A1 → Gear **R** (Pleos 카메라 화면이 뜸) → Gear **P** | P 후 앱이 다시 앞으로 나오고, 경로 끝이 "후진 주차" |
| A9 | 시연 모드: 화면 왼쪽 위 상태 칩(`주행 중`/`저장된 위치`) 을 누릅니다 (숨겨진 시연 시작 버튼, 다시 누르면 중지) | 앱 안 시나리오 약 40초 → `B2 · C-15`, "추정 B2와 일치" |
| A10 | 진입 판단 (위치): 에뮬레이터 `…` → Location에서 고도를 38 → 33으로 낮추고, 그 사이 Odometer를 1km 올립니다 | 진입 근거 "고도 급강하"로 진입 (경로는 1km 점프가 생기므로 경로 확인용은 아님) |

## B. 실제 주행처럼 이어서 하는 테스트 (스크립트)

```
powershell -ExecutionPolicy Bypass -File tools\emulator-drive.ps1
```

같은 신호 경로(SDK)로 아래를 약 1분 동안 자동으로 재생합니다. Virtual Console API는 Emulation Controls 카드가 쓰는 것과 같은 `PUT /vehicle/...` 요청입니다.

1. 앱을 새로 시작하고 내비게이션으로 가립니다.
2. 도로 36km/h, GPS가 움직임
3. 주차장 입구: GPS가 멈추고 고도가 내려감 → **앱이 스스로 진입을 판단** → 자동 실행 + 진입 알림 + 음성
4. 나선 램프 2바퀴 (핸들 30°), 표지판 B1, B2
5. B2 통로, 표지판 C-12 ~ C-14
6. C-15 칸을 지나쳐 멈춤 → **기어 R → 후진 주차** (Pleos 후방·좌우 카메라가 뜸). 표지판에 일부러 넣은 오독 `CI5`가 있음
7. **기어 P** → 앱이 다시 앞으로 나옴 → 운전석 문 열림 → 저장 + 음성

확인한 결과 (2026-11-14):

- 첫 실행: 진입 근거 **고도 급강하**
- 두 번째 실행부터: **등록된 주차장 진입**. 첫 주차 때 입구를 학습해서 FusedLocation 근접 알림으로 알아봅니다.
- `B2 · C-15`, "추정 B2와 일치", 기둥 표지판 3회 인식
- 경로: "입구에서 직진 103m → 나선 램프 2바퀴 (내려감) → 직진 39m → 우회전 후 5m → **후진 주차**"
- 3D 화면: 주변 차량 12대 (Kenney Car Kit 모델), 내 차는 파란색으로 통로를 향해 주차

옵션: `-NoRestart` (앱을 다시 시작하지 않음), `-TickMs 1500` (천천히), `-Lat/-Lon` (출발 좌표)

## C. 알아 둘 것

- **Virtual Console API는 브라우저 세션이 있어야 받아들입니다.** 없으면 `emulator_inactive` 오류가 납니다. 스크립트는 `POST /browser/session?client=<id>`를 매 단계 보냅니다.
- 차체(`/vehicle/body`) 값은 `true`/`false`로 보내야 합니다. `0`/`1`은 받지 않습니다. 속도는 정수만 받습니다.
- 기어 R에서는 Pleos 카메라 3개가 켜져 에뮬레이터가 무거워집니다. RAM이 부족하면 에뮬레이터가 꺼질 수 있으니 다른 프로그램을 닫고 시연하세요.
- HOME 키로는 앱이 뒤로 가지 않습니다. 내비게이션 앱을 띄워서 가리세요.
- adb로 화면을 캡처하면 부팅 직후에는 검은 화면이 나올 수 있습니다.
- 앱 로그: `adb logcat -s ParkTrace/Service ParkTrace/Vehicle ParkTrace/Navi ParkTrace/UI`
- 저사양 PC: 에뮬레이터 RAM 4GB, 코어 6개, `-gpu host`가 가장 부드러웠습니다. 다른 에뮬레이터를 같이 켜면 메모리가 부족해집니다.
