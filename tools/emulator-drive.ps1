<#
  여기세웠지 - Pleos Connect 에뮬레이터 "실제 주행" 시연 스크립트

  실제 신호 경로로 주행을 흉내 냅니다 (앱 안의 '시연 모드'와 달리 SDK를 그대로 거칩니다).
    - GPS (위도/경도/고도)        : adb emu geo fix          -> Pleos FusedLocation SDK
    - 속도/기어/핸들/브레이크/문   : Virtual Console HTTP API -> Pleos Vehicle SDK
    - 누적 주행거리               : Virtual Console HTTP API -> Vehicle SDK registerDistanceDriven
                                   (에뮬레이터는 트립 주행거리를 지원하지 않아 앱이 누적 주행거리로 대신 계산)
    - 기둥 표지판 글자            : adb broadcast SIM_SIGN    (카메라 자리, 모의 인식)

  시나리오 (약 1분): 도로 주행 -> 공영주차장 입구(지붕 아래 GPS 정체) -> 앱 자동 실행 + 진입 알림
                    -> 입구 차단기 정차 -> 직선 경사로로 지하 1층 (B1 표지) -> B1 통로 (C-12..C-14) -> 칸을 지나쳐 후진 주차 (C-15)
                    -> 기어 P -> 운전석 문 열림 -> 저장 + 음성

  사용법:   powershell -ExecutionPolicy Bypass -File tools\emulator-drive.ps1
  옵션:     -NoRestart   앱을 다시 시작하지 않음 (기본: 깨끗한 상태로 다시 시작)
            -TickMs 1000 한 단계 간격 (ms)
            -CameraWaitSec 4  기어 R 후 차량 카메라 뷰가 뜰 때까지 기다리는 시간 (초)
#>
param(
    [string]$Serial = "",
    [switch]$NoRestart,
    # 주차 후 35초 뒤 다시 탑승까지 시연 (예상 주차 요금 알림)
    [switch]$Return,
    [int]$TickMs = 1000,
    # 기어 R 후 차량 카메라 뷰가 화면에 뜰 때까지 기다리는 시간 (초)
    [int]$CameraWaitSec = 4,
    # 출발 위치 (임의의 서울 도로 좌표). 북쪽으로 달립니다.
    [double]$Lat = 37.5105,
    [double]$Lon = 127.1030
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$B = "http://127.0.0.1:8088"
$client = [guid]::NewGuid().ToString()
$pkg = "com.parktrace"
$recv = "com.parktrace/.service.DebugEntryReceiver"

# ---------------------------------------------------------------- 기기 찾기
if (-not $Serial) {
    foreach ($s in (& $adb devices | Select-String '^(emulator-\d+)\s+device' | ForEach-Object { $_.Matches[0].Groups[1].Value })) {
        $name = (& $adb -s $s emu avd name | Select-Object -First 1)
        if ("$name".Trim() -like "Pleos*") { $Serial = $s }
    }
}
if (-not $Serial) { throw "Pleos Connect 에뮬레이터를 찾지 못했어요. 먼저 에뮬레이터를 켜 주세요." }
Write-Host "에뮬레이터: $Serial"
& $adb -s $Serial forward tcp:8088 tcp:8088 | Out-Null

function Hb { try { Invoke-WebRequest -UseBasicParsing -Method Post "$B/browser/session?client=$client" -TimeoutSec 5 | Out-Null } catch {} }
function Put([string]$q) {
    # 콘솔은 브라우저 세션이 3초만 유지됨. R에서 Pleos 카메라가 켜지면 에뮬레이터가 느려지므로 요청마다 갱신하고,
    # 세션이 잠깐 끊겨 거절되면(emulator_inactive) 다시 잡고 재시도
    for ($try = 1; $try -le 4; $try++) {
        Hb
        try { Invoke-WebRequest -UseBasicParsing -Method Put "$B$q" -TimeoutSec 5 | Out-Null; return }
        catch { if ($try -eq 4) { Write-Warning "PUT $q -> $($_.ErrorDetails.Message)" } else { Start-Sleep -Milliseconds 300 } }
    }
}
function Sign([string]$text) {
    & $adb -s $Serial shell "am broadcast -a com.parktrace.action.SIM_SIGN --es text '$text' -n $recv" | Out-Null
    Write-Host "    표지판(모의 인식): $text"
}
function Fmt([double]$v, [int]$d) { $v.ToString("F$d", [Globalization.CultureInfo]::InvariantCulture) }

# ---------------------------------------------------------------- 상태
Hb
$state = Invoke-RestMethod "$B/vehicle/state" -TimeoutSec 5
$odoKm = [double]$state.odometer.traveled_distance_km
$alt = 38.0
$lat = $Lat; $lon = $Lon
$lastWheel = 0.0

function Tick([double]$meters, [double]$speedKph, [double]$wheelDeg, [bool]$gpsMoves, [double]$altStep) {
    # wheelDeg: + = 오른쪽 (앱/SDK 기준). Virtual Console은 부호가 반대라 뒤집어서 보냄.
    $script:odoKm += $meters / 1000.0
    $script:alt += $altStep
    if ($gpsMoves) { $script:lat += $meters / 111000.0 }
    Hb
    Put ("/vehicle/controls?speed={0}&accelerator={1}&brake=0&steering={2}" -f [int][Math]::Round($speedKph), $(if ($speedKph -gt 0) { 15 } else { 0 }), [int](-$wheelDeg))
    # 핸들 각도가 바뀐 틱에는 그 값이 앱에 먼저 도착하도록 잠깐 기다린 뒤 주행거리를 올림
    # (빠른 PC에서는 주행거리 신호가 먼저 도착해 회전이 한 틱 덜 기록됨: 90도가 46도로)
    if ($wheelDeg -ne $script:lastWheel) { Start-Sleep -Milliseconds 450; $script:lastWheel = $wheelDeg }
    Put ("/vehicle/odometer?traveled_distance_km=" + (Fmt $script:odoKm 4))
    & $adb -s $Serial emu geo fix (Fmt $script:lon 6) (Fmt $script:lat 6) (Fmt $script:alt 1) | Out-Null
    Start-Sleep -Milliseconds $TickMs
}

function Idle([int]$seconds) {
    for ($i = 0; $i -lt $seconds; $i++) {
        Hb
        & $adb -s $Serial emu geo fix (Fmt $script:lon 6) (Fmt $script:lat 6) (Fmt $script:alt 1) | Out-Null
        Start-Sleep -Milliseconds $TickMs
    }
}

# ---------------------------------------------------------------- 준비
Write-Host "준비: 문/프렁크/트렁크 닫힘, 기어 D, 출발 위치"
Put "/vehicle/body?door_fl=false&door_fr=false&door_rl=false&door_rr=false&frunk=false&trunk=false"
Put "/vehicle/gear?gear=Drive"
Put "/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0"
& $adb -s $Serial emu geo fix (Fmt $lon 6) (Fmt $lat 6) (Fmt $alt 1) | Out-Null

if (-not $NoRestart) {
    Write-Host "앱을 깨끗한 상태로 다시 시작"
    & $adb -s $Serial shell am force-stop --user 11 $pkg
    & $adb -s $Serial shell appops set --user 11 $pkg SYSTEM_ALERT_WINDOW allow
    & $adb -s $Serial shell am start --user 11 -n "$pkg/.ui.MainActivity" | Out-Null
    Idle 8
}
Write-Host "내비게이션을 앞에 띄움 (앱은 뒤에서 대기)"
& $adb -s $Serial shell am start --user 11 -n "ai.umos.maps.android.navigation.app/.MainActivity" | Out-Null
Idle 3

# ---------------------------------------------------------------- 1. 도로 (GPS 정상)
Write-Host "1. 도로 주행 36km/h"
for ($i = 0; $i -lt 8; $i++) { Tick 10 36 0 $true 0 }
Write-Host "   감속 15km/h, 공영주차장 입구 접근"
for ($i = 0; $i -lt 3; $i++) { Tick 4.2 15 0 $true 0 }

# ---------------------------------------------------------------- 2. 공영주차장 입구 (지붕 아래 GPS 정체) -> 진입 판단
Write-Host "2. 공영주차장 입구: 지붕 아래로 들어가 GPS 위치가 멈춤 -> 앱이 진입을 판단해야 함"
Tick 4 10 0 $false -0.1; Sign "공영주차장 입구|높이제한 2.3M"
Tick 4 8 0 $false -0.1
Write-Host "   입구 차단기 앞 정차 (번호판 인식 입차) -> 차단기 열림"
Hb; Put "/vehicle/controls?speed=0&accelerator=0&brake=30&steering=0"; Idle 2
Sign "입차 완료"
Tick 3 8 0 $false 0

# ---------------------------------------------------------------- 3. 직선 경사로로 지하 1층
Write-Host "3. 직선 경사로를 내려가 지하 1층"
for ($i = 0; $i -lt 4; $i++) { Tick 4.5 12 0 $false -0.8 }
Sign "B1"
Tick 4 10 0 $false 0
Write-Host "   지하 1층 도착, 오른쪽으로 꺾어 통로로"
for ($i = 0; $i -lt 2; $i++) { Tick 3.95 10 30 $false 0 }
Sign "B1|C-1"

# ---------------------------------------------------------------- 4. B1 통로
Write-Host "4. B1 통로 직진"
for ($i = 0; $i -lt 6; $i++) {
    Tick 5 12 0 $false 0
    switch ($i) { 1 { Sign "B1|C-12" } 3 { Sign "B1-C13" } 5 { Sign "C14" } }
}

# ---------------------------------------------------------------- 5. 칸을 지나쳐 멈춘 뒤 후진 주차 (기어 R)
Write-Host "5. C-15 칸을 지나쳐 멈춤 -> 기어 R -> 핸들 오른쪽으로 후진 주차"
Tick 1.6 8 0 $false 0; Sign "C-15"
Tick 1.27 5 0 $false 0; Sign "B1|CI5"
Hb; Put "/vehicle/controls?speed=0&accelerator=0&brake=40&steering=0"; Idle 1
Put "/vehicle/gear?gear=Reverse"
Write-Host "   주차 모드: 차량 카메라 뷰가 뜰 때까지 ${CameraWaitSec}초 대기한 뒤 후진"
Idle $CameraWaitSec
for ($i = 0; $i -lt 3; $i++) { Tick 2.168 5 35 $false 0 }
Tick 1.36 4 0 $false 0; Sign "C15"

# ---------------------------------------------------------------- 6. 정차 -> P -> 문 열림
Write-Host "6. 정차 -> 기어 P: 바로 저장 + 음성 안내, Pleos 카메라가 닫히고 앱이 다시 앞으로"
Hb; Put "/vehicle/controls?speed=0&accelerator=0&brake=40&steering=0"; Idle 2
Put "/vehicle/gear?gear=Park"
Put "/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0"
Idle 5
Write-Host "7. 운전석 문 열림 -> 운전자 하차"
Put "/vehicle/body?door_fl=true"
Idle 3
Put "/vehicle/body?door_fl=false"
if ($Return) {
    Write-Host "8. 운전자가 자리를 비움 (35초) -> 다시 탑승 (운전석 문 열림) -> 예상 주차 요금 알림 + 음성"
    Idle 35
    Put "/vehicle/body?door_fl=true"
    Idle 4
    Put "/vehicle/body?door_fl=false"
} else {
    Write-Host "   (내린 지 30초가 지난 뒤 Virtual Console에서 운전석 문을 열면 예상 주차 요금 알림이 떠요. 자동으로 하려면 -Return)"
}
Write-Host "완료. 앱 화면에서 지하 1층 · C-15, 3D 위치, 요금을 확인하세요. '폰으로 보내기'로 QR을 띄울 수 있어요."
