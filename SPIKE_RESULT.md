# MotionCare 기술 확인 결과 (Spike)

- 확인일: 2026-11-14
- 환경: Pleos Connect Emulator `Pleos_Connect_v2.1.0` (API 34, user 빌드), Vehicle SDK `2.3.2`, NaviHelper `3.0.5`
- 앱: 이 폴더의 `app/` (패키지 `com.motioncare.spike`). 빌드·설치·실행 확인함.
- 근거 파일은 모두 `spike_evidence/`에 있다. 로그 태그는 `MCSPIKE`다.
- 표기: **확인 안 됨** = 이번에 직접 보지 못했거나 추정한 내용.
- 이 보고서가 말하는 시험 앱 코드(`com.motioncare.spike`, 버튼 이름, `ShakeEstimator.kt` 경로)는 git 태그 `spike`에 있다. 지금 `app/`은 이 결과로 만든 덜울렁 1차 데모 앱이다(README 참고).
- 맨 아래 '1차 제출 준비에서 새로 확인한 것(2026-11-15)'에 이후 확인 결과를 더했다.

## 요약표

| # | 항목 | 결과 | 근거 | 쓴 API · 권한 | 막힌 이유 · 대안 |
|---|---|---|---|---|---|
| 1 | 차속·조향각 읽기 | **됨** | `logcat_drive_scenario.txt`의 TICK 줄, `app_main.png` | 차속: `CarPropertyManager` `PERF_VEHICLE_SPEED`(m/s), `android.car.permission.CAR_SPEED`(dangerous, 런타임 요청). 조향: `SteeringWheel.getCurrentSteeringWheelAngle` / `registerSteeringWheelAngle`, `pleos.car.permission.CAR_DRIVE`(normal) | 조향값은 **앞바퀴각이 아니라 핸들각**이다(범위 -470~500°). 부호가 콘솔과 반대다(콘솔 +150° → SDK -150°). |
| 2 | 흔들림 계산 | **됨** | `logcat_drive_scenario.txt` (SCN 구간별) | 1번 값으로 앱 안에서 계산. `ShakeEstimator.kt` | 축거 API가 없다. 2.95 m 상수 사용. 조향비 15:1은 가정이다(**확인 안 됨**). |
| 3 | 엠비언트 조명 제어 | **됨** | `console_ambient_red.jpg`, `console_ambient_green.jpg`, `logcat_drive_scenario.txt`의 `set AmbientColor` 줄 | `Light.setAmbientLightBrightnessColor`, `setAmbientLightSwitch`, `setAmbientLightBrightness`. `CAR_LIGHTS`(+`CONTROL_CAR_LIGHTS` 선언), 모두 normal | 색은 **Virtual Console 화면에만** 보인다. 에뮬레이터 HMI에는 표시가 없다. 콘솔 페이지가 열려 있어야(세션 활성) 반영된다. |
| 4 | 공조 제어 | **됨** | `logcat_hvac.txt` | `HVAC.setHvacRecirculation`, `setHvacFanSpeed`, `setHvacPower`. 쓰기 `CONTROL_CAR_CLIMATE`, 읽기 `CAR_CLIMATE`, 둘 다 normal | 풍량은 공조 전원이 꺼져 있으면 `NOT_AVAILABLE(2)`로 실패한다. 전원을 켜면 된다. |
| 5 | 주행 규제 상태 | **부분** | `logcat_startup_caps_and_ux.txt`, `dumpsys_permissions_ux.txt`, `reg_on_plain_noDO*.png` | `CarUxRestrictionsManager` (권한 불필요) | 규제 상태 읽기는 됨. CLAUDE.md의 `hmg_car_service` 명령은 이 에뮬레이터에 **없다**. DO 메타데이터를 뺀 화면도 **차단되지 않았다**(원인 **확인 안 됨**). |
| 덤 | 뒷좌석 탑승 감지 | **부분** | `logcat_seat_occupancy.txt` | `Seat.getCurrentSeatOccupancy` / `registerSeatOccupancy`, `CAR_SEATS`(normal) | 이벤트는 오지만 **SDK가 주는 Boolean이 반대**다. VHAL 원시값은 정상. |
| 덤 | NaviHelper 연동·TBT | **부분** | `logcat_navi.txt`, `logcat_navi_tbt.txt`, `navi_tbt_route_started.png` | `NaviHelper.initialize`, `requestRoute`, `getTBTInfo` → `onTBTInfo`, `cancelRoute`. `NAVI_ROUTE`·`NAVI_ROUTE_SEARCH`(둘 다 normal) | 경로 요청과 TBT 수신(회전 7개, 종류·구간 거리·방면)은 됨. 에뮬레이터에서 차 위치를 움직일 수 없어 **주행 중 거리 갱신은 확인 안 됨**. |

---

## 1. 차속·조향각 읽기: 됨

**결과**
- 차속: `PERF_VEHICLE_SPEED` 설정 rate 10~20 Hz. `SENSOR_RATE_FASTEST`로 등록하니 초당 약 10건이 들어왔다.
- 콘솔 속도를 바꾸면 앱 숫자가 바로 따라온다. 예: 콘솔 60→100 km/h를 2.5초 동안 올리면 앱이 62→71→78→84→90→98→100으로 따라왔다.
- 조향: `checkSteeringWheelAngleCapabilities` → `access=READ bounds=-470.0~500.0`.
  - 콘솔 카탈로그에서도 이 값은 `PERF_STEERING_ANGLE`(0x11600209), 범위 -470~500이다. 즉 **핸들각(도)** 이다. CLAUDE.md의 "앞바퀴 각도"는 맞지 않는다.
  - 부호는 콘솔과 반대다. 콘솔 `steering=150` → SDK `-150.0`.
  - `registerSteeringWheelAngle` 리스너는 값이 바뀔 때만 온다. 10 Hz로 `getCurrentSteeringWheelAngle`를 폴링해도 문제없었다(약 10회/초 응답).

**주의**
- 콘솔 속도는 정수 km/h라서 차속 해상도가 0.28 m/s다. 종가속도에 ±0.5 m/s² 정도 계단 잡음이 생긴다(아래 2번 수치에 반영됨).

## 2. 흔들림 계산: 됨

**방법** (`app/src/main/java/com/motioncare/spike/ShakeEstimator.kt`)
- 앞바퀴각 δ = 핸들각 ÷ 15 (조향비 상수 `STEERING_RATIO`, 핸들각 범위가 ±90°를 넘을 때만 적용)
- 횡가속도 = v²·tan(δ)/L, L = 2.95 m (`WHEELBASE_M`). CarInfo에는 VIN·CRN 조회만 있고 축거 API가 없다.
- 종가속도 = 0.5초 구간 차속 차분 → 3샘플(0.3초) 이동평균.
- 흔들림 = √(횡² + 종²). 기준 `SHAKE_THRESHOLD = 2.0` m/s² (상수 하나로 변경).
  - 기준 미만 초록, 기준 이상 주황, 기준 ×1.5 이상 빨강. 단계가 내려갈 때는 1.5초 유지(깜빡임 방지).

**시나리오 결과** (`tools/drive_scenario.py`로 콘솔에 10 Hz 입력, 로그 `logcat_drive_scenario.txt`)

| 구간 | 입력 | 앱이 계산한 값 | 단계 |
|---|---|---|---|
| A 부드러운 가속 | 20→60 km/h, 16초(≈0.7 m/s²) | 종 0.18~1.07 | 계속 초록 |
| B 급가속 | 60→100 km/h, 2.5초(≈4.4 m/s²) | 종 3.4~4.6 | 시작 0.6초 뒤 주황→빨강 |
| C 급제동 | 100→40 km/h, 2초(≈-8.3 m/s²) | 종 -6.4~-7.5 | 시작 0.4초 뒤 주황→빨강 |
| D 완만한 커브 | 40 km/h, 핸들 0→30° 4초 | 횡 최대 1.46 | 계속 초록 |
| E 급조향 | 60 km/h, 핸들 0→150° 0.8초 | 횡 4.1→16.6 | 시작 0.4초 뒤 주황→빨강 |
| F 완만한 정지 | 60→0 km/h, 25초(≈-0.67 m/s²) | 종 0.18~0.89 | 계속 초록 |

- 급한 구간이 끝나고 약 1.5~2초 뒤 초록으로 돌아왔다.
- 합격 기준(급가속·급제동·급조향 때 튀고 부드러우면 낮다)을 만족한다.

**한계**
- E의 16.6 m/s²는 물리적으로 과하다. 타이어 미끄럼을 무시한 운동학 식이고 조향비 15가 가정이라서다. 실차 조향비는 **확인 안 됨**.
- 첫 시나리오 시작 때 콘솔 속도가 240→20 km/h로 한 번에 바뀌어 종가속도 -118 m/s²가 찍혔다. 실제 앱에는 비현실 값(예: |a|>12 m/s²) 버리기가 필요하다.
- 콘솔 페이지를 열어 둔 채 스크립트로 속도를 넣으면, 페이지의 자체 물리 계산과 섞여 속도가 튄다(예: 25→10→26 km/h). 자동 시험은 페이지를 닫고 스크립트가 heartbeat를 보내게 했다.

## 3. 엠비언트 조명 제어: 됨 (시연 핵심)

**결과**
- `checkAmbientLightBrightnessColorCapabilities` → `READ_WRITE`, 영역은 **5좌석을 묶은 SeatArea 하나**(`[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT]`)다.
  - `SeatArea(ROW_1_LEFT)` 단독으로 쓰면 `IllegalArgumentException: area ID: 0x1 not supported for property ID: 0x25400783`로 실패한다. capability가 준 영역을 그대로 써야 한다.
- 색 값 형식: **0xRRGGBB 정수**. 0xD50000을 쓰면 콘솔 상태가 `#d50000`, car_service 마지막 이벤트가 `0x25400783 int32Values: [16748288]`(=0xFF8F00, 주황일 때)로 바뀌었다.
- 초기 스위치가 **OFF**였다. `setAmbientLightSwitch(ON)`과 `setAmbientLightBrightness(100)`(범위 0~100)를 먼저 했다.
- `set` 콜백까지 3~27 ms. 흔들림 단계가 바뀔 때만 썼다.
- `getAmbientColorAutoByDriveModeEnabled`는 미지원(`property ID is not in carPropertyConfig list: 0x21200787`).

**어디에 보이나**
- **Virtual Console 웹 화면**에 보인다(`console_ambient_red.jpg`, `console_ambient_green.jpg`).
  - SYSTEMS 패널 AMBIENT 칸의 색 칩
  - 패널 테두리 발광
  - BODY 패널 차량 탑뷰의 실내등 막대
- 에뮬레이터 화면(HMI)에는 색이 나타나지 않는다(`app_main.png`). 앱 안 색 상자는 대안 표시로 넣어 두었다.

**주의 (시연 때 중요)**
- 콘솔 페이지(브라우저 세션)가 없으면 콘솔이 `emulator_inactive`가 된다. 이때 앱의 `set`은 OK 콜백을 받지만 car_service 값은 바뀌지 않았다(흰색 0xFFFFFF, 밝기 70 유지). 페이지를 열자 그제야 반영됐다. **시연 중에는 콘솔 페이지를 계속 열어 둔다.**
- 앱이 사용자의 엠비언트 설정(스위치·밝기·색)을 바꾸므로, 실제 앱은 주행 종료 때 원래 값으로 되돌려야 한다.

## 4. 공조 제어: 됨

**결과** (`logcat_hvac.txt`)
- 내기순환: `checkHvacRecirculationCapabilities` → `READ_WRITE`, 5좌석 묶음 영역.
  - `get=false → setHvacRecirculation(true) OK → 재조회 true`, 콘솔 `recirc_on: True`, `air_source: Inside`.
  - 반대로 `true → false`도 확인.
- 풍량: `checkHvacFanSpeedCapabilities` → `READ_WRITE`, 영역 `[ROW_1_LEFT, ROW_1_RIGHT]`, 범위 1~8.
  - 공조 전원 OFF 상태에서 `setHvacFanSpeed(4)` → `PropertyAsyncError ... HVAC_FAN_SPEED ... error code: 2`(NOT_AVAILABLE).
  - `setHvacPower(true)` 후 다시 쓰니 `OK → 4`, 재조회 4, 콘솔 `fan_speed 4`, `power_on True`.
- 자동 내기순환(`checkHvacAutoRecirculationCapabilities`)도 `READ_WRITE`로 지원된다(쓰기는 시험 안 함).
- 권한: 읽기 `CAR_CLIMATE`, 쓰기 `CONTROL_CAR_CLIMATE`. 에뮬레이터에서 둘 다 `prot=normal`. PRIVATE 아님.

**대안·주의**
- 풍량 조절 전에 공조 전원을 켜야 한다. 사용자가 꺼 둔 공조를 앱이 켜는 셈이라 기획서에 "켜도 되는지" 정책을 적어야 한다.

## 5. 주행 규제 상태: 부분

**됨**
- `CarUxRestrictionsManager`로 상태를 읽고 변화 이벤트를 받는다(권한 불필요).
- 규제는 **콘솔 기어로 켜고 끈다**:
  - P, 0 km/h → `requiresDO=false active=0x0`
  - D, 40~50 km/h → `requiresDO=true active=0x50`
  - 0x50 = `NO_VIDEO | NO_SETUP`. car_service 설정도 parked 0x0, idling·moving 0x50이다.

**안 됨 / 확인 안 됨**
- `adb shell cmd hmg_car_service --service drivingstate driving_regulation on` → `cmd: Can't find service: hmg_car_service`. 이 에뮬레이터(v2.1.0)에는 그 서비스가 없다. 다른 버전용 명령일 수 있다(**확인 안 됨**).
- `cmd car_service emulate-driving-state` → `requires non-user build`로 막힘.
- DO 비교:
  - `MainActivity`(distractionOptimized=true)와 `PlainActivity`(메타데이터 없음) 모두 D·50 km/h에서 **그대로 보였다**(`reg_on_main_DO.png`, `reg_on_plain_noDO.png`).
  - P에서 PlainActivity를 띄운 뒤 D로 바꿔도 차단 화면이 뜨지 않았다(`reg_on_plain_noDO_after_transition.png`).
  - `CarPackageManagerService`는 `mEnableActivityBlocking:true`, `Display 0 is restricted`인데 `Blocked activity log`가 비어 있다.
  - 원인 추정: Pleos 런처가 앱을 multi-window 태스크로 띄워 AAOS 기본 차단이 적용되지 않는 것 같다. 또는 차단을 `hmg_car_service` 같은 Pleos 전용 경로가 맡는 것 같다. **둘 다 확인 안 됨.**

**기획서에 쓸 대안**
- 차단 여부와 상관없이, 앱이 `requiresDO`를 보고 주행 중에는 화면을 최소 정보(단계 색 하나)로 줄이는 방식은 확실히 된다.
- 메타데이터는 계속 넣는다. 실차나 다른 에뮬레이터 버전에서 차단이 걸릴 수 있다.

## 덤 1. 뒷좌석 탑승 감지: 부분

- `checkSeatOccupancyCapabilities` → `READ`, 영역 `ROW_2_LEFT / ROW_2_RIGHT / ROW_2_CENTER / ROW_1_LEFT / ROW_1_RIGHT`.
- 콘솔에서 뒷좌석 왼쪽을 바꾸면 이벤트가 바로 온다. 그런데 **값이 반대**다:

  | 콘솔 rear_left | VHAL `SEAT_OCCUPANCY`(0x15400BB0) 원시값 | SDK Boolean |
  |---|---|---|
  | 탑승(true) | 2 = OCCUPIED (정상) | **false** |
  | 비움(false) | 1 = VACANT (정상) | **true** |

- SDK 2.3.2가 값을 뒤집는 것으로 보인다. 쓰려면 반전해서 쓰고, Pleos에 문의해 확인해야 한다.
- 콘솔의 좌석 쓰기 형식: `PUT /vehicle/seat?kind=occupied&area=rear_left&value=true`

## 덤 2. NaviHelper 연동: 부분 (연결·경로 요청·TBT 수신 됨, 주행 중 거리 갱신 확인 안 됨)

근거: `logcat_navi.txt`, `logcat_navi_tbt.txt`, `navi_tbt_route_started.png`, `navi_tbt_after_gps_move.png`

**연결**
- `NaviHelper(context).initialize(onConnected, onFailed)` → `navi connected`.
- 연결 직후 콜백: `onCurrentLocationInfo`(위도 37.4127, 경도 127.0947, '수정구 시흥동'), `onRouteStateInfo`(naviMode=SAFE_GUIDING), `onCurrentRoadSpeedLimit 0`.

**목적지 걸기 (SDK `requestRoute`)**
- 앱의 '내비 경로' 버튼으로 `requestRoute(RouteInfo(37.394776, 127.111148, …, "판교역", …, RouteOption.RECOMMENDED, …))`를 불렀다.
- 약 6초 뒤 콜백 순서:
  1. `onRouteStarted` (duration=600초, distance=3100m, name=판교역)
  2. `onRouteEnded` (시작 직후에 바로 온다. 의미는 **확인 안 됨**. 안내 종료로 해석하면 안 된다)
  3. `onNavigationStatus ROUTE_DRIVE`
  4. `onRouteStateInfo naviMode=ROUTE_GUIDING`. 안내 중에도 `isRouting=false`라서, 안내 여부는 `naviMode`나 `NavigationStatus`로 판단해야 한다.
  5. `onTBTInfo` (0.3초 뒤)
  6. `onDrivingInfo` (남은 시간 600초, 남은 거리 3100m)
- `requestRoute`를 부르면 **내비 앱이 화면 앞으로 나오고 우리 앱은 일시정지된다.** 그래도 바인더 콜백과 Handler 폴링은 계속 돈다.
- 권한: `NAVI_ROUTE`(get*), `NAVI_ROUTE_SEARCH`(requestRoute·cancelRoute). 둘 다 normal.

**TBT 콜백 (`onTBTInfo(List<TBTInfo>)`)**
- `TBTInfo` 필드: `type: TurnType`(enum), `distance: Double`(m), `description: String`(방면 이름).
- 실제로 받은 목록(7개):

  | 순서 | type | distance | description |
  |---|---|---|---|
  | 1 | TURN_LEFT | 80 | 기업지원허브 방면 |
  | 2 | TURN_RIGHT | 380 | 판교IC 방면 |
  | 3 | TURN_AVOID_UNDER_R | 730 | 판교IC 방면 |
  | 4 | TURN_LEFT | 1200 | 판교(판교테크노밸리)역 방면 |
  | 5 | TURN_RIGHT | 530 | 백현동행정복지센터 방면 |
  | 6 | TURN_RIGHT | 120 | 이름 없는 도로 |
  | 7 | TURN_ARRIVE | 60 | 도착 |

- 거리는 **누적 거리가 아니라 구간 거리**다. 첫 항목은 현재 위치에서 첫 회전까지, 다음 항목부터는 앞 회전에서 다음 회전까지다. 7개를 더하면 3100m로 경로 길이와 같다.
  - 그래서 n번째 회전까지 남은 거리는 앞 항목들을 더해서 구해야 한다.
- 내비 화면(`navi_tbt_route_started.png`)의 '80m 기업지원허브 방면 / 380m'과 같고, 클러스터에도 같은 값이 떴다(`navi_tbt_cluster.png` 왼쪽).
- `getTBTInfo()`를 부를 때마다 `onTBTInfo`가 한 번씩 왔다. 2초 폴링에서 15회 조회하는 동안 콜백 수가 10 → 15 → 20으로 늘었다.
  - 조회 없이 내비가 스스로 보내는 콜백이 있는지는 구분하지 못했다(**확인 안 됨**).
- 앱을 다시 켜도 진행 중인 경로가 있으면, 연결 직후 `getTBTInfo()`로 같은 7개를 바로 받는다.
- 경로를 취소하면 `onTBTInfo` 빈 목록(n=0), `onRouteCancelled`, `onNavigationStatus FREE_DRIVE`가 온다.
- `TurnType`에는 좌우 회전 외에도 KEEP·SHARP·UTURN·IC 진출입·지하/고가차도·회전교차로(TURN_ENTER_ROUNDABOUT_*) 값이 있다. 급회전(SHARP)과 회전교차로를 미리 알 수 있다.

**주행 중 거리 갱신: 확인 안 됨 (에뮬레이터에서 차 위치를 움직일 수 없음)**
- 콘솔에서 D·40km/h로 40초 달려도 TBT 거리는 80m 그대로였다. 내비 엔진 로그도 위치가 `org(127.094681,37.4127212)`, `speed(0.00)`, `sat(0)`으로 고정이다. 콘솔 차속은 내비 위치에 반영되지 않는다.
- `adb emu geo fix`로 좌표를 옮기면 안드로이드 GPS와 Pleos fused 위치 서비스까지는 바뀐다. 하지만 내비 앱이 에뮬레이터의 NMEA 문장을 `NMEA checksum mismatch`로 버려서 지도 위치가 그대로다(`navi_tbt_after_gps_move.png`). 에뮬레이터 쪽 체크섬 문제로 보인다.
- 체크섬을 맞춘 문장을 `adb emu geo nmea`로 직접 보내 봤지만 GNSS HAL이 위치를 갱신하지 않았다.
- 콘솔 카탈로그(63개 기능)에도 위치·GPS 입력이 없고, 내비 설정에도 모의 주행 메뉴가 없었다(내비 3.3.2-rc5-hotfix.3).
- 따라서 '다음 회전까지 남은 거리가 줄어들며 갱신되는지', '얼마나 자주 오는지'는 실차나 위치를 바꿀 수 있는 환경에서 다시 봐야 한다.

**주의**
- 한 번은 SDK `cancelRoute()` 뒤 내비가 `cancelTrip is already working` 상태로 멈췄다. 이후 SDK 취소와 화면의 '안내 종료' 버튼이 모두 듣지 않았다. 내비 앱을 강제 종료하고 다시 띄운 뒤 '경로 안내 재시작' 창에서 '안내 종료'를 눌러 풀었다. 재현 조건은 **확인 안 됨**.

**기획서에 쓸 수 있는 것**
- 경로 안내 중이면 앞으로 나올 회전의 종류와 구간 거리를 미리 받을 수 있다. 이것으로 "곧 급회전(SHARP·UTURN·회전교차로)이 나온다"를 운전자에게 미리 알리는 기능을 생각해 볼 수 있다.
- 실시간으로 남은 거리가 줄어드는지는 확인 전이라서, 기획서에는 "경로 시작 시 회전 목록 제공(확인됨), 주행 중 갱신(확인 필요)"으로 나눠 적는다.

---

## Virtual Console HTTP API (새로 확인한 것)

- 쓰기는 `PUT`이고 값은 쿼리 문자열로 보낸다.
  - 주행: `PUT /vehicle/controls?speed=<km/h>&accelerator=<%>&brake=<%>&steering=<deg>`
  - 기어: `PUT /vehicle/gear?gear=Park|Reverse|Neutral|Drive`
  - 바디: `PUT /vehicle/body?ambient_color=%23rrggbb` 등
  - 좌석: `PUT /vehicle/seat?kind=occupied&area=rear_left&value=true`
- **브라우저 세션이 있어야 쓰기를 받는다.** 없으면 `{"error":"emulator_inactive"}`.
  - 페이지 대신 `POST /browser/session?client=<아무이름>`을 1초마다 보내면 활성 상태가 유지된다(타임아웃 3초).
- `/vehicle/catalog`에 기능별 `write_endpoint`, 필드 이름, VHAL 프로퍼티 ID가 다 있다.

## 1차 제출 준비에서 새로 확인한 것 (2026-11-15)

| 항목 | 결과 | 근거 | 내용 |
|---|---|---|---|
| 콘솔 속도 튐 | **해결** | `docs/evidence/console_jitter_test.txt`, `spike_evidence/console_viewer_mode.png` | 원인: 콘솔 페이지의 `physicsTick()`이 차가 움직이는 동안 자체 계산 속도를 `PUT /vehicle/controls`로 계속 다시 쓴다. `tools/console_viewer.py`(보기 전용 프록시)로 페이지의 물리 계산과 쓰기를 끄니 125회 중 튐 22회(최대 33 km/h)에서 0회가 됐다. |
| release APK | **됨** | `spike_evidence/logcat_release_apk_check.txt`, `docs/evidence/release_demo_timeline.txt` | 서명 release(R8 끔)를 지우고 새로 `adb install -g`로 설치. 차속, 핸들각, 무드등, 공조 호출이 debug와 같이 동작. `-g`로 CAR_SPEED가 허용되고 Pleos 권한은 normal이라 자동 허용. |
| 콘솔 비활성과 차속 이벤트 | 확인 | (수동 확인) | 콘솔 세션이 없어도(`emulator_active: false`) `PERF_VEHICLE_SPEED` 이벤트는 약 10 Hz로 계속 온다. 그래서 콘솔로는 '신호 끊김'을 만들 수 없다. 덜울렁은 debug 전용 브로드캐스트로 시험했다. |
| 콘솔 쓰기 형식 | 확인 | `tools/drive_scenario.py` | 공조 `PUT /vehicle/hvac?power=true&recirc=true&fan_speed=3`, 무드등 `PUT /vehicle/body?ambient_mode=On&ambient_brightness=60&ambient_color=%23ffffff`, 좌석 `area`는 `rear_left`, `rear_right`만 받는다(`rear_center`는 `invalid_seat_area`). |
| 2열 탑승 값 | 부분 | `docs/evidence/edge_tests_debug.txt` | 반전은 그대로다. 콘솔 좌석을 바꾼 직후 앱을 켜면 첫 조회가 이전 값으로 한 번 나온 뒤 이벤트로 바로잡힌 적이 있다. 덜울렁은 1초 이상 유지된 값만 쓴다. |
| 알림음 | 됨(앱 쪽) | 앱 logcat `AudioTrack ... frames delivered` | `ToneGenerator(STREAM_NOTIFICATION)`가 소리를 내보낸다. PC 스피커로 들리는지는 확인 안 됨. |
| 뒤로 키와 onDestroy | 확인 | `docs/evidence/edge_tests_debug.txt` | Android 12부터 첫 화면에서 뒤로 키는 앱을 끝내지 않고 뒤로 보낸다(onDestroy 안 불림, 케어 계속). onDestroy 복원은 debug 전용 `TEST_FINISH`로 시험했다. |

## 재현 방법

(아래는 태그 `spike`의 시험 앱 기준이다. 덜울렁 앱의 실행 방법은 README에 있다.)

1. 에뮬레이터 실행, `adb forward tcp:8088 tcp:8088`
2. 빌드·설치 (CLI는 JDK 21 사용. Android Studio 내장 JBR 25는 Gradle 8.13과 맞지 않는다)
   ```
   JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew assembleDebug
   adb install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```
3. 앱 실행 후 주행 시나리오: `python tools/drive_scenario.py` (콘솔 페이지는 닫아 둔다)
4. 로그: `adb logcat -s MCSPIKE`
5. 공조: 앱의 '공조 테스트' 버튼. 엠비언트 수동: '초록/주황/빨강' 버튼, '자동'으로 복귀.
6. 내비 TBT: 앱의 '내비 경로' 버튼(판교역으로 경로 요청), '경로 취소' 버튼. 로그는 `adb logcat -s MCSPIKE | grep navi`.

## 기획서 반영 제안 (사실 기반)

- 차속·핸들각·엠비언트·내기순환·풍량은 모두 normal 권한으로 된다. 실제 앱 기능 목록에 넣어도 된다.
- 엠비언트 시연은 **Virtual Console 화면을 함께 띄워서** 보여 줘야 한다.
- 주행 중 화면 차단은 이 에뮬레이터에서 재현되지 않는다. "앱이 스스로 주행 중 화면을 줄인다"로 설명하는 편이 안전하다.
- 뒷좌석 탑승 감지는 값 반전 문제가 있어 "확인 중" 기능으로 둔다.
