#!/usr/bin/env python3
"""도착 직전 미리 시치미(11/13 추가)를 에뮬레이터에서 확인한다.

혼자만의 노래방(기어 D) 중에 내비 목적지를 넣으면, 남은 시간 − 60초가 지난 때 문지기가 「잠시 가수 복귀」(RETURN)로 가고
가수 음량이 1.0이 된다. 안내를 끝내면(경로 없음) 1.5초 뒤 다시 노래방. 목적지는 verify_scene7과 같은 방법(내비 화면을 탭)으로 넣는다.

  python3 tools/verify_arrival.py docs/evidence/arrival
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
sys.argv = [sys.argv[0]] + sys.argv[1:]
import verify_scene7 as s7  # noqa: E402

vs = s7.vs
# 에뮬레이터 데이터를 초기화한 뒤(11/12 22:40) 기본 위치가 판교 근처라 장면 7의 목적지가 목록에 없다. 음식점 분류 1번(약 2분).
s7.DEST = "스타벅스 판교아이스퀘어점"
OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/evidence/arrival"
vs.OUT = OUT


def log():
    return vs.sh("adb", "logcat", "-d", "-v", "time", "-s", "SC.Gate:I", "SC.Navi:I")


def wait(pattern, timeout):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if re.search(pattern, log()):
            return time.time() - t0
        time.sleep(1.0)
    return None


def main():
    os.makedirs(OUT, exist_ok=True)
    rows = []

    def row(scene, did, expect, ok, note=""):
        rows.append((scene, did, expect, "통과" if ok else "**실패**", note))
        print(scene, did, "->", "통과" if ok else "실패", note)

    print("내비 첫 화면:", s7.reset_navi())
    for seat, v in (("driver", "on"), ("passenger", "off"), ("rear_left", "off"), ("rear_right", "off")):
        vs.c("seat", seat, v)
    for z in ("front_left", "front_right", "rear_left", "rear_right"):
        vs.c("door", z, "close"); vs.c("window", z, "0")
    vs.c("turn", "off"); vs.c("gear", "D")
    vs.sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{vs.PKG}/.MainActivity"); time.sleep(4)
    vs.sh("adb", "logcat", "-c")
    if not vs.tap_text("재생"):
        vs.tap_text("일시정지"); time.sleep(1); vs.tap_text("재생")
    t = vs.wait_state("KARAOKE", 12)
    row("A0", "운전석만 착석, 문·창문 닫힘, 기어 D에서 재생", "노래방", t is not None, f"재생 후 {t:.1f}초" if t else "")
    ok = s7.set_destination()
    t_dest = time.time()
    got = wait(r"남은 시간 \S+ 정보: [1-9]\d*초", 20)
    secs = [int(x) for x in re.findall(r"남은 시간 \S+ 정보: ([1-9]\d*)초", log())]
    remain = secs[-1] if secs else None
    row("A1", f"내비 목적지 '{s7.DEST}'", "남은 시간을 받음", ok and remain is not None, f"{remain}초" if remain else "")
    vs.sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{vs.PKG}/.MainActivity"); time.sleep(2)
    vs.shot("a1_karaoke_with_route")
    if remain:
        due = remain - 60
        left = due - (time.time() - t_dest)
        print(f"곧 도착까지 약 {left:.0f}초 기다림")
        early = wait(r"상태 KARAOKE -> RETURN", max(1.0, left - 15))
        row("A2", "곧 도착 시각 전", "노래방 유지", early is None, "")
        hit = wait(r"곧 도착.*\n.*\n?.*상태 KARAOKE -> RETURN|상태 KARAOKE -> RETURN", 60)
        l = log()
        at = time.time() - t_dest
        ramp = re.search(r"상태 KARAOKE -> RETURN[\s\S]*?가수 음량 -> 1\.0 \((\d+)ms\)", l)
        row("A3", f"남은 시간 {remain}초 − 60초 = {due}초", "잠시 가수 복귀, 가수 음량 1.0", hit is not None and ramp is not None,
            f"목적지 넣고 {at:.0f}초에 RETURN, 램프 {ramp.group(1)}ms" if ramp else "")
        time.sleep(1.5)
        vs.shot("a2_return_before_arrival")
        texts = vs.screen_texts()
        row("A4", "그때 화면", "주행 화면 '잠시 가수 복귀'", "잠시 가수 복귀" in texts, ", ".join(t for t in texts if "가수" in t or "노래방" in t))
    with open(os.path.join(OUT, "arrival_log.txt"), "w") as f:
        f.write(log())
    vs.sh("adb", "logcat", "-c")
    s7.open_navi(3)
    b = s7.bounds_of("안내 종료")
    if b:
        s7.tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, 3)
    else:
        s7.tap(1216, 1224, 3)
    vs.sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{vs.PKG}/.MainActivity")
    back = wait(r"상태 RETURN -> KARAOKE", 15)
    l = log()

    def ts(pat):
        m = re.search(r"(\d\d):(\d\d):(\d\d\.\d+).*" + pat, l)
        return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3)) if m else None
    a, b2 = ts("경로 취소"), ts("상태 RETURN -> KARAOKE")
    row("A5", "안내 종료(경로 없음)", "1.5초 뒤 다시 노래방", back is not None, f"경로 취소 뒤 {b2 - a:.1f}초(로그)" if a and b2 else "")
    vs.c("gear", "P")
    with open(os.path.join(OUT, "arrival_log.txt"), "a") as f:
        f.write("--- 안내 종료 뒤 ---\n" + log())
    md = ["| 단계 | 한 일 | 기대 | 결과 | 비고 |", "|---|---|---|---|---|"] + [f"| {a} | {b} | {e} | {r} | {n} |" for a, b, e, r, n in rows]
    with open(os.path.join(OUT, "result.md"), "w") as f:
        f.write("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
