#!/usr/bin/env python3
"""Pleos Connect Virtual Console을 셸에서 다루는 작은 도구.

콘솔 웹 페이지가 쓰는 것과 같은 HTTP 경로(PUT)를 부른다. 검증 스크립트가 쓴다.
시연은 사람이 콘솔 페이지에서 직접 누른다.

전제: `adb forward tcp:8088 tcp:8088`

사용 예
  tools/console.py state                      # 지금 상태 요약
  tools/console.py heartbeat                  # 콘솔 페이지 대신 하트비트를 계속 보낸다(DDS ONLINE 유지)
  tools/console.py seat passenger on          # 좌석: driver passenger rear_left rear_right
  tools/console.py door front_right open      # 문: front_left front_right rear_left rear_right
  tools/console.py window front_left 30       # 창문(%)
  tools/console.py gear D                     # 기어: P R N D
  tools/console.py turn left                  # 방향지시등: off left right
"""
import json
import sys
import time
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8088"
DOOR_FIELD = {"front_left": "door_fl", "front_right": "door_fr", "rear_left": "door_rl", "rear_right": "door_rr"}
WINDOW_FIELD = {"front_left": "window_fl", "front_right": "window_fr", "rear_left": "window_rl", "rear_right": "window_rr"}
GEAR = {"P": "Park", "R": "Reverse", "N": "Neutral", "D": "Drive"}
TURN = {"off": "Off", "left": "BlinkLeft", "right": "BlinkRight"}


def put(path, **params):
    url = f"{BASE}{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, method="PUT")
    with urllib.request.urlopen(req, timeout=5) as r:
        return r.status


def state():
    with urllib.request.urlopen(f"{BASE}/vehicle/state", timeout=5) as r:
        return json.load(r)


def summary():
    s = state()
    body = s["body"]
    return {
        "dds_online": s["gear"]["bridge_ready"],
        "gear": (s["gear"].get("actual") or {}).get("value"),
        "seats_occupied": s["seat"]["occupied"],
        "doors_open": body["doors"],
        "windows_pct": body["windows"],
        "turn_signal": s.get("lights", {}).get("turn_signal_mode"),
    }


def on(v):
    return str(v).lower() in ("1", "on", "open", "true", "occupied", "yes")


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    cmd, args = argv[0], argv[1:]
    if cmd == "state":
        print(json.dumps(summary(), ensure_ascii=False, indent=1))
    elif cmd == "heartbeat":
        client = args[0] if args else "shell"
        while True:
            try:
                urllib.request.urlopen(urllib.request.Request(
                    f"{BASE}/browser/session?client={client}", method="POST"), timeout=3).close()
            except OSError as e:
                print("heartbeat 실패:", e, file=sys.stderr)
            time.sleep(1)
    elif cmd == "seat":
        put("/vehicle/seat", kind="occupied", value=str(on(args[1])).lower(), area=args[0])
    elif cmd == "door":
        put("/vehicle/body", **{DOOR_FIELD[args[0]]: str(on(args[1])).lower()})
    elif cmd == "window":
        put("/vehicle/body", **{WINDOW_FIELD[args[0]]: int(args[1])})
    elif cmd == "gear":
        put("/vehicle/gear", gear=GEAR[args[0].upper()])
    elif cmd == "turn":
        put("/vehicle/turn-signal", mode=TURN[args[0].lower()])
    else:
        print("모르는 명령:", cmd, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
