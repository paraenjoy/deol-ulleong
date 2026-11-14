"""Speed jitter test: script writes a ramp at 10 Hz and reads back the server speed.
Counts readbacks that differ from the last commanded value by more than 2 km/h."""
import json
import sys
import threading
import time
import urllib.request

BASE = "http://127.0.0.1:8088"


def call(path, method="GET"):
    req = urllib.request.Request(BASE + path, method=method)
    with urllib.request.urlopen(req, timeout=2) as r:
        return r.read().decode()


def hb():
    while True:
        try:
            call("/browser/session?client=jitter-test", "POST")
        except Exception:
            pass
        time.sleep(1)


threading.Thread(target=hb, daemon=True).start()
time.sleep(1.5)
print("gear", call("/vehicle/gear?gear=Drive", "PUT")[:80])
seq = [20 + i * 0.5 for i in range(80)] + [60 + i * 1.6 for i in range(25)] + [100 - i * 3 for i in range(20)]
bad = 0
worst = 0
for v in seq:
    call(f"/vehicle/controls?speed={v:.0f}&accelerator=20&brake=0&steering=0", "PUT")
    time.sleep(0.05)
    s = json.loads(call("/vehicle/state"))["controls"]["speed_kph"]
    d = abs(float(s) - round(v))
    worst = max(worst, d)
    if d > 2:
        bad += 1
        print(f"cmd {v:.0f} got {s}")
    time.sleep(0.05)
print(f"samples={len(seq)} mismatches={bad} worst={worst}")
for v in range(40, -1, -4):
    call(f"/vehicle/controls?speed={v}&accelerator=0&brake=30&steering=0", "PUT")
    time.sleep(0.1)
call("/vehicle/gear?gear=Park", "PUT")
