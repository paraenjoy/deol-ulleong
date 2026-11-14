"""덜울렁 예외 동작 시험: 케어 종료 버튼, 화면만 설정, 신호 끊김, 공조 사용자 우선, 공조 꺼짐, 앱 종료 복원.

전제: drive_scenario.py와 같다(adb forward, debug 빌드 설치, 동의 완료 상태). 콘솔 값을 읽어 복원 여부를 판정한다.
신호 끊김은 콘솔로 만들 수 없어 debug 빌드 전용 브로드캐스트(TEST_SIGNAL_LOSS)로 흉내 낸다.
실행: python tools/edge_tests.py   결과: PASS/FAIL 줄과 tools/out/edge_tests.txt
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request

BASE = "http://127.0.0.1:8088"
PKG = "com.motioncare.deolulleong"
TEAL, WHITE = "#00bfa5", "#ffffff"
results = []


def call(path, method="PUT"):
    req = urllib.request.Request(BASE + path, method=method)
    with urllib.request.urlopen(req, timeout=3) as r:
        return r.read().decode()


def state():
    return json.loads(call("/vehicle/state", "GET"))


def ambient():
    return state()["body"]["ambient_light"]["color"].lower()


def hvac():
    h = state()["hvac"]
    return h["power_on"], h["recirc_on"], h["fan_speed"]


def heartbeat_loop():
    while True:
        try:
            call("/browser/session?client=edge-tests", "POST")
        except BaseException:  # noqa: BLE001
            pass
        time.sleep(1)


def adb(*args):
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    return subprocess.run(["adb", *args], capture_output=True, env=env, text=True, encoding="utf-8")


def tap_text(label, timeout=10):
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
    return False


def logs():
    return adb("logcat", "-d", "-v", "raw", "-s", "DEOL").stdout


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}", flush=True)


def drive_to(v, secs, steer=0):
    st = state()["controls"]
    v0, s0 = st["speed_kph"], st["steering_deg"]
    n = max(1, int(secs * 10))
    for i in range(1, n + 1):
        t = i / n
        call(f"/vehicle/controls?speed={v0 + (v - v0) * t:.0f}&accelerator=30&brake=0&steering={s0 + (steer - s0) * t:.0f}")
        time.sleep(0.1)


def reset_console(rear=False, power=True):
    call("/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0")
    call("/vehicle/gear?gear=Park")
    call(f"/vehicle/seat?kind=occupied&area=rear_left&value={'true' if rear else 'false'}")
    call(f"/vehicle/hvac?power={'true' if power else 'false'}&recirc=true&fan_speed=3")
    call("/vehicle/body?ambient_mode=On&ambient_brightness=60&ambient_color=%23ffffff")


def restart_app():
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(4)


def main():
    threading.Thread(target=heartbeat_loop, daemon=True).start()
    time.sleep(1.5)
    sys.stdout.reconfigure(encoding="utf-8")

    # 1. 케어 종료 버튼 -> 복원, 그 뒤 자동 재시작 안 함
    reset_console()
    restart_app()
    call("/vehicle/seat?kind=occupied&area=rear_left&value=true")
    time.sleep(2.5)
    check("2열 탑승 -> 케어 시작, 무드등 청록", ambient() == TEAL, ambient())
    tap_text("케어 종료")
    time.sleep(1.5)
    check("케어 종료 버튼 -> 무드등 원래 색", ambient() == WHITE, ambient())
    time.sleep(2)
    check("케어 종료 뒤 같은 탑승으로 자동 재시작 안 함", ambient() == WHITE and logs().count("케어 시작") == 1)

    # 2. 지금 케어 시작, 무드등 끄고 화면만
    tap_text("지금 케어 시작")
    time.sleep(2)
    check("'지금 케어 시작' -> 청록", ambient() == TEAL, ambient())
    tap_text("무드등 끄고 화면만")
    time.sleep(1.5)
    check("'무드등 끄고 화면만' 켬 -> 원래 색", ambient() == WHITE, ambient())
    tap_text("무드등 끄고 화면만")
    time.sleep(2)
    check("'무드등 끄고 화면만' 끔 -> 청록", ambient() == TEAL, ambient())

    # 3. 신호 끊김(2초) -> 제어 멈춤, 복원, '신호 확인 중' / 신호 복구 -> 다시 제어
    call("/vehicle/gear?gear=Drive")
    drive_to(30, 2)
    time.sleep(1)
    adb("shell", "am", "broadcast", "-a", "com.motioncare.deolulleong.TEST_SIGNAL_LOSS", "--el", "ms", "6000")
    time.sleep(3.5)
    lg = logs()
    check("신호 끊김 -> '신호 확인 중' 로그", "신호 확인 중" in lg)
    check("신호 끊김 -> 무드등 원래 색", ambient() == WHITE, ambient())
    with open(os.path.join(OUT, "signal_lost.png"), "wb") as f:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f)
    time.sleep(5)
    check("신호 복구 -> 다시 청록", ambient() == TEAL, ambient())

    # 4. 공조 케어 뒤 사용자가 풍량을 바꾸면 더 건드리지 않고 복원도 안 함
    drive_to(75, 2.5)                       # 급가속
    drive_to(75, 2.5)
    drive_to(75, 0.6, steer=30)             # 급조향
    drive_to(75, 2, steer=30)
    drive_to(75, 0.6, steer=0)
    time.sleep(1.5)
    p, r, fan = hvac()
    check("멀미 부하 -> 외기 유입, 풍량 +1", (r is False) and fan == 4, f"power={p} recirc={r} fan={fan}")
    time.sleep(1)
    call("/vehicle/hvac?power=true&recirc=false&fan_speed=6")  # 사용자가 풍량 6으로
    time.sleep(1.5)
    check("사용자 변경 감지", "사용자가 공조를 바꿈" in logs())
    drive_to(0, 3)
    call("/vehicle/gear?gear=Park")
    time.sleep(2)
    p, r, fan = hvac()
    check("정차 -> 공조는 사용자 값 유지(복원 안 함)", fan == 6 and "hvac 복원 안 함" in logs(), f"fan={fan}")
    check("정차 -> 무드등 원래 색", ambient() == WHITE, ambient())

    # 5. 공조 전원 꺼짐 -> 공조 케어 건너뜀
    call("/vehicle/hvac?power=false&recirc=true&fan_speed=3")
    time.sleep(1)
    call("/vehicle/gear?gear=Drive")
    drive_to(30, 1)
    drive_to(75, 2.5)
    drive_to(75, 2.5)
    drive_to(75, 0.6, steer=30)
    drive_to(75, 2, steer=30)
    drive_to(75, 0.6, steer=0)
    time.sleep(1)
    p, r, fan = hvac()
    check("공조 꺼짐 -> 공조 케어 건너뜀", "공조 전원이 꺼져" in logs() and r is True and fan == 3, f"power={p} recirc={r} fan={fan}")

    # 6. 앱 종료(onDestroy) -> 복원. Android 12부터 뒤로 키는 첫 화면을 끝내지 않으므로 debug 훅으로 finish()
    check("주행 중 무드등 케어 중", ambient() in (TEAL, "#ffb300", "#ff6d00"), ambient())
    adb("shell", "am", "broadcast", "-a", "com.motioncare.deolulleong.TEST_FINISH")
    time.sleep(2.5)
    check("앱 종료(onDestroy) -> 무드등 원래 색", ambient() == WHITE, ambient())
    check("앱 종료 로그", "ambient 복원(앱 종료)" in logs())

    drive_to(0, 2)
    call("/vehicle/gear?gear=Park")
    call("/vehicle/hvac?power=true&recirc=true&fan_speed=3")
    with open(os.path.join(OUT, "edge_tests.txt"), "w", encoding="utf-8") as f:
        for name, ok, detail in results:
            f.write(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}\n")
        f.write("\n--- logcat -s DEOL ---\n" + logs())
    fails = sum(1 for _, ok, _ in results if not ok)
    print(f"\n{len(results) - fails}/{len(results)} PASS", flush=True)
    sys.exit(1 if fails else 0)


OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
os.makedirs(OUT, exist_ok=True)

if __name__ == "__main__":
    main()
