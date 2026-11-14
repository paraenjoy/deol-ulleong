"""Virtual Console에 주행 시나리오를 10Hz로 넣는다.

전제: adb forward tcp:8088 tcp:8088. 콘솔 페이지는 닫아 둔다.
      콘솔은 브라우저 세션 heartbeat(POST /browser/session)가 없으면 emulator_inactive로 쓰기를 거부하므로
      이 스크립트가 직접 heartbeat를 보낸다. 페이지가 열려 있으면 페이지의 물리 계산이 속도를 덮어써 값이 튄다.
쓰기 형식: PUT /vehicle/controls?speed=<km/h>&accelerator=<%>&brake=<%>&steering=<deg>
각 구간 시작 시 logcat에 'MCSPIKE SCN <이름>'을 남겨 앱 로그와 맞춰 본다.
"""
import subprocess
import sys
import threading
import time
import urllib.request

BASE = "http://127.0.0.1:8088"
HZ = 10


def heartbeat_loop():
    """콘솔 페이지 대신 브라우저 세션 heartbeat를 보낸다(페이지의 자체 물리 계산이 끼어들지 않게)."""
    while True:
        try:
            req = urllib.request.Request(f"{BASE}/browser/session?client=spike-script", method="POST")
            urllib.request.urlopen(req, timeout=2).read()
        except Exception as e:  # noqa: BLE001
            print("heartbeat fail", e, flush=True)
        time.sleep(1)


def put(speed, steer, accel=0, brake=0):
    q = f"speed={speed:.0f}&accelerator={accel}&brake={brake}&steering={steer:.0f}"
    req = urllib.request.Request(f"{BASE}/vehicle/controls?{q}", method="PUT")
    with urllib.request.urlopen(req, timeout=2) as r:
        body = r.read().decode()
        if "error" in body[:30]:
            sys.exit(f"console refused: {body[:80]}")


def mark(name):
    print(f"--- {name}", flush=True)
    subprocess.run(["adb", "shell", "log", "-p", "i", "-t", "MCSPIKE", f"SCN {name}"])


def ramp(name, v0, v1, s0, s1, secs, accel=0, brake=0):
    mark(name)
    n = max(1, int(secs * HZ))
    for i in range(n + 1):
        t = i / n
        put(v0 + (v1 - v0) * t, s0 + (s1 - s0) * t, accel, brake)
        time.sleep(1 / HZ)


def hold(name, v, s, secs):
    ramp(name, v, v, s, s, secs)


if __name__ == "__main__":
    threading.Thread(target=heartbeat_loop, daemon=True).start()
    time.sleep(2)
    hold("0_idle_20kph", 20, 0, 3)
    ramp("A_smooth_accel_20to60_16s", 20, 60, 0, 0, 16, accel=20)   # ~0.7 m/s²
    hold("A_hold_60", 60, 0, 3)
    ramp("B_hard_accel_60to100_2.5s", 60, 100, 0, 0, 2.5, accel=100)  # ~4.4 m/s²
    hold("B_hold_100", 100, 0, 3)
    ramp("C_hard_brake_100to40_2s", 100, 40, 0, 0, 2, brake=100)    # ~-8.3 m/s²
    hold("C_hold_40", 40, 0, 3)
    ramp("D_smooth_curve_in_40kph_30deg_4s", 40, 40, 0, 30, 4)       # 핸들 30° → 앞바퀴 2°
    hold("D_hold_curve", 40, 30, 3)
    ramp("D_smooth_curve_out", 40, 40, 30, 0, 4)
    ramp("E_to_60", 40, 60, 0, 0, 20, accel=15)
    ramp("E_hard_steer_60kph_0to150_0.8s", 60, 60, 0, 150, 0.8)      # 핸들 150° → 앞바퀴 10°
    hold("E_hold_steer", 60, 150, 2)
    ramp("E_steer_back", 60, 60, 150, 0, 0.8)
    hold("E_settle", 60, 0, 3)
    ramp("F_gentle_stop_60to0_25s", 60, 0, 0, 0, 25, brake=10)       # ~-0.67 m/s²
    mark("END")
