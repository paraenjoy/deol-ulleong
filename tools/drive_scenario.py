"""덜울렁 시연 대본: Virtual Console에 주행 장면을 0.1초 간격으로 넣고, 장면 타임라인을 남긴다.

준비
  1) 에뮬레이터 실행, adb forward tcp:8088 tcp:8088
  2) python tools/console_viewer.py 를 켜고 브라우저로 http://127.0.0.1:8090/ 을 연다(보기 전용 콘솔, 녹화용).
     원래 콘솔 페이지(8088)를 열어 두면 페이지의 물리 계산이 속도를 덮어써서 값이 튄다.
  3) 덜울렁 앱 설치(adb install -r -g ...)

실행
  python tools/drive_scenario.py              # 동의, 피드백 버튼은 사람이 누른다(안내가 나오면 Enter)
  python tools/drive_scenario.py --auto-tap   # 동의, 피드백 버튼도 스크립트가 누른다(리허설, 자동 시험용)
  옵션: --keep-consent  앱 데이터를 지우지 않는다(동의 화면 생략)
        --with-brake    정차 장면 끝에 급제동을 넣는다

장면 순서
  동의 -> 2열 탑승 -> 부드러운 출발(청록) -> 급가속(호박, 진한 주황, 알림음) -> 회복 -> 급조향
  -> 멀미 부하로 공조 바뀜(콘솔 CLIMATE 칸) -> 정차(기어 P) -> 리포트 -> 피드백 버튼

결과
  장면 시작 시각과 앱 로그(무드등 색, 공조 케어, 주행 종료)를 시작 기준 초로 정리해 화면과 tools/out/demo_timeline.txt 에 남긴다.
  console 쓰기 형식: PUT /vehicle/controls?speed=<km/h>&accelerator=<%>&brake=<%>&steering=<deg>
"""
import argparse
import os
import re
import shlex
import subprocess
import sys
import threading
import time
import urllib.request

BASE = "http://127.0.0.1:8088"
PKG = "com.motioncare.deolulleong"
HZ = 10
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")

timeline = []  # (초, 종류, 내용)
t0 = None
state = {"speed": 0.0, "steer": 0.0}


def now():
    return 0.0 if t0 is None else time.time() - t0


def note(kind, text):
    timeline.append((now(), kind, text))
    print(f"[{now():6.1f}s] {kind:4} {text}", flush=True)


# ------------------------------------------------------------------ console

def call(path, method="PUT"):
    req = urllib.request.Request(BASE + path, method=method)
    with urllib.request.urlopen(req, timeout=3) as r:
        body = r.read().decode()
    if body.startswith('{"error"'):
        sys.exit(f"console refused {path}: {body[:120]}")
    return body


def heartbeat_loop():
    """콘솔은 브라우저 세션이 없으면 쓰기를 거부한다. 보기 전용 페이지가 없어도 되도록 직접 보낸다."""
    while True:
        try:
            call("/browser/session?client=drive-scenario", "POST")
        except BaseException:  # noqa: BLE001
            pass
        time.sleep(1)


def put(speed, steer, accel=0, brake=0):
    state["speed"], state["steer"] = speed, steer
    call(f"/vehicle/controls?speed={speed:.0f}&accelerator={accel}&brake={brake}&steering={steer:.0f}")


def ramp(v1, s1, secs, accel=0, brake=0):
    v0, s0 = state["speed"], state["steer"]
    n = max(1, int(secs * HZ))
    for i in range(1, n + 1):
        t = i / n
        put(v0 + (v1 - v0) * t, s0 + (s1 - s0) * t, accel, brake)
        time.sleep(1 / HZ)


def hold(secs, accel=0):
    ramp(state["speed"], state["steer"], secs, accel)


def gear(g):
    call(f"/vehicle/gear?gear={g}")


# ------------------------------------------------------------------ adb

def adb(*args, text=True):
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    return subprocess.run(["adb", *args], capture_output=True, env=env, text=text, encoding="utf-8" if text else None)


SHOTS = {"dir": None}


def shot(name):
    """--shots 옵션일 때 에뮬레이터 화면을 저장한다(백그라운드, 주행 입력을 막지 않음)."""
    d = SHOTS["dir"]
    if not d:
        return
    path = os.path.join(d, f"{name}.png")

    def run():
        with open(path, "wb") as f:
            subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f)
    threading.Thread(target=run, daemon=True).start()


def scene(name):
    note("장면", name)
    adb("shell", "log -p i -t DEOL " + shlex.quote(f"SCN {name}"))


def tap_text(label, timeout=10):
    """uiautomator로 화면에서 글자를 찾아 가운데를 누른다(Compose 화면도 됨)."""
    end = time.time() + timeout
    while time.time() < end:
        adb("shell", "uiautomator", "dump", "/sdcard/deol_ui.xml")
        xml = adb("shell", "cat", "/sdcard/deol_ui.xml").stdout or ""
        m = re.search(r'text="%s"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(label), xml)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            return True
        time.sleep(0.5)
    print(f"!! '{label}' 버튼을 찾지 못했다", flush=True)
    return False


def wait_user_or_tap(label, auto):
    if auto:
        time.sleep(1.5)
        tap_text(label)
    else:
        input(f">> 앱에서 '{label}'을(를) 누른 뒤 Enter")


LOG_PATTERNS = [
    (re.compile(r"케어 시작"), "앱"),
    (re.compile(r"ambient (잔잔|주의|거침) .*OK"), "무드등"),
    (re.compile(r"공조 케어"), "공조"),
    (re.compile(r"hvac: 공조 전원이 꺼져"), "공조"),
    (re.compile(r"주행 #\d+ 끝"), "앱"),
    (re.compile(r"ambient 복원|hvac 복원"), "복원"),
    (re.compile(r"피드백 기록"), "앱"),
    (re.compile(r"신호 확인 중"), "앱"),
]


logcat_proc = None


def logcat_watch():
    global logcat_proc
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    p = logcat_proc = subprocess.Popen(["adb", "logcat", "-T", "1", "-v", "raw", "-s", "DEOL"], stdout=subprocess.PIPE,
                                       stderr=subprocess.DEVNULL, env=env, encoding="utf-8", errors="replace")
    for line in p.stdout:
        line = line.strip()
        for pat, kind in LOG_PATTERNS:
            if pat.search(line):
                note(kind, line[:150])
                break


# ------------------------------------------------------------------ main

def prepare(keep_consent):
    print("준비: 기어 P, 정지, 2열 비움, 공조 켜짐(내기순환, 풍량 3), 무드등 흰색 60%", flush=True)
    put(0, 0)
    gear("Park")
    for area in ("rear_left", "rear_right"):
        call(f"/vehicle/seat?kind=occupied&area={area}&value=false")
    call("/vehicle/hvac?power=true&recirc=true&fan_speed=3")
    call("/vehicle/body?ambient_mode=On&ambient_brightness=60&ambient_color=%23ffffff")
    adb("shell", "am", "force-stop", PKG)
    if not keep_consent:
        adb("shell", "pm", "clear", PKG)
        adb("shell", "pm", "grant", PKG, "android.car.permission.CAR_SPEED")
    time.sleep(1)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(4)


def main():
    global t0
    ap = argparse.ArgumentParser()
    ap.add_argument("--auto-tap", action="store_true")
    ap.add_argument("--keep-consent", action="store_true")
    ap.add_argument("--with-brake", action="store_true")
    ap.add_argument("--shots", metavar="DIR", help="장면별 에뮬레이터 화면을 DIR에 저장")
    a = ap.parse_args()
    if a.shots:
        os.makedirs(a.shots, exist_ok=True)
        SHOTS["dir"] = a.shots

    threading.Thread(target=heartbeat_loop, daemon=True).start()
    time.sleep(1.5)
    prepare(a.keep_consent)
    if not a.auto_tap:
        input(">> 녹화를 시작한 뒤 Enter")
    threading.Thread(target=logcat_watch, daemon=True).start()
    t0 = time.time()

    scene("1 동의")
    shot("01_consent")
    if not a.keep_consent:
        wait_user_or_tap("동의하고 시작", a.auto_tap)
    time.sleep(2)

    scene("2 2열 탑승 (콘솔 좌석: 뒷좌석 왼쪽)")
    call("/vehicle/seat?kind=occupied&area=rear_left&value=true")
    time.sleep(3)
    shot("02_care_ready")
    time.sleep(1)

    scene("3 부드러운 출발 (청록)")
    gear("Drive")
    time.sleep(1)
    ramp(40, 0, 14, accel=25)          # 약 0.8 m/s^2
    shot("03_driving_calm")
    hold(2, accel=15)

    scene("4 급가속 (호박, 진한 주황, 알림음)")
    ramp(80, 0, 2.5, accel=100)        # 약 4.4 m/s^2
    shot("04_driving_rough")
    scene("5 회복")
    hold(5, accel=20)

    scene("6 급조향")
    ramp(80, 25, 0.6, accel=20)        # 핸들 25도 -> 바퀴 1.7도, 80 km/h에서 좌우 약 4.9 m/s^2
    hold(2, accel=20)
    ramp(80, 0, 0.6, accel=20)

    scene("7 멀미 부하로 공조 바뀜 (콘솔 CLIMATE 칸)")
    hold(5, accel=20)

    scene("8 정차")
    if a.with_brake:
        ramp(30, 0, 10, brake=15)
        ramp(0, 0, 1.5, brake=100)     # 약 5.6 m/s^2
    else:
        ramp(0, 0, 16, brake=20)       # 약 1.4 m/s^2
    time.sleep(1)
    gear("Park")

    scene("9 리포트")
    time.sleep(3)
    shot("09_report")
    time.sleep(1)

    scene("10 피드백 버튼")
    wait_user_or_tap("조금 울렁", a.auto_tap)
    time.sleep(2)
    shot("10_feedback")
    time.sleep(1)
    note("끝", "")

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "demo_timeline.txt")
    with open(path, "w", encoding="utf-8") as f:
        for t, kind, text in timeline:
            f.write(f"{int(t) // 60:02d}:{int(t) % 60:02d}  {t:6.1f}s  {kind}  {text}\n")
    print(f"타임라인: {path}", flush=True)
    if logcat_proc:
        logcat_proc.kill()


if __name__ == "__main__":
    main()
