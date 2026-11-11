#!/usr/bin/env python3
"""핵심 장면(설계 8절의 1~5와 8)을 가상 콘솔 신호로 처음부터 끝까지 돌리고 화면과 상태를 남긴다.

콘솔 조작은 콘솔 페이지와 같은 HTTP 경로로 한다. 앱 안의 가짜 입력은 쓰지 않는다.
앱에서 사람이 하는 일(재생 누르기, 탭 누르기)은 화면 탭으로 한다.

  tools/verify_scenes.py docs/evidence/scenes [--keep-data]
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import console  # noqa: E402

PKG = "app.sichimi"
OUT = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else "docs/evidence/scenes"


def sh(*a, check=True):
    return subprocess.run(list(a), check=check, capture_output=True, text=True).stdout


def c(*a):
    console.main(list(a))
    time.sleep(0.15)


def shot(name):
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)


def gate_log():
    return sh("adb", "logcat", "-d", "-v", "time", "-s", "SC.Gate:I")


def last_state():
    s = re.findall(r"상태 \w+ -> (\w+)", gate_log())
    return s[-1] if s else "NORMAL"


def wait_state(name, timeout):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if last_state() == name:
            return time.time() - t0
        time.sleep(0.1)
    return None


def tap_text(text):
    sh("adb", "shell", "uiautomator", "dump", "--user", "10", "/sdcard/ui.xml", check=False)
    xml = sh("adb", "shell", "cat", "/sdcard/ui.xml")
    m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(text), xml)
    if not m:
        return False
    x1, y1, x2, y2 = map(int, m.groups())
    sh("adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True


def screen_texts():
    sh("adb", "shell", "uiautomator", "dump", "--user", "10", "/sdcard/ui.xml", check=False)
    xml = sh("adb", "shell", "cat", "/sdcard/ui.xml")
    return [t for t, p in re.findall(r'text="([^"]+)"[^>]*package="([^"]+)"', xml) if p == PKG]


def main():
    os.makedirs(OUT, exist_ok=True)
    rows = []

    def row(scene, did, expect, ok, note=""):
        rows.append((scene, did, expect, "통과" if ok else "**실패**", note))
        print(scene, did, "->", "통과" if ok else "실패", note)

    # 준비: 기록 없이 시작, 장면 1의 차 상태
    if "--keep-data" not in sys.argv:
        sh("adb", "shell", "pm", "clear", "--user", "10", PKG, check=False)
    for seat, v in (("driver", "on"), ("passenger", "off"), ("rear_left", "off"), ("rear_right", "off")):
        c("seat", seat, v)
    for z in ("front_left", "front_right", "rear_left", "rear_right"):
        c("door", z, "close"); c("window", z, "0")
    c("turn", "off"); c("gear", "D")
    sh("adb", "logcat", "-c")
    sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{PKG}/.MainActivity")
    time.sleep(4)

    # 장면 1
    t_play = time.time()
    ok = tap_text("재생") or bool(sh("adb", "shell", "input", "tap", "1693", "812") or True)
    t = wait_state("KARAOKE", 12)
    shot("s1_karaoke")
    texts = screen_texts()
    row(1, "운전석만 착석, 문·창문 닫힘, 기어 D에서 재생", "5초 뒤 노래방, '혼자만의 노래방'",
        t is not None and "혼자만의 노래방" in texts, f"재생 후 {t:.1f}초" if t else "")

    # 장면 2
    c("door", "front_right", "open")
    t = wait_state("NORMAL", 2)
    time.sleep(0.5)
    shot("s2_door_open")
    texts = screen_texts()
    row(2, "동승석 문 열기", "곧바로 일반, 평범한 재생 화면",
        t is not None and "재생 중" in texts and not any("노래방" in x for x in texts), f"{t * 1000:.0f}ms 안에 전이" if t is not None else "")

    # 장면 3
    c("seat", "passenger", "on"); c("door", "front_right", "close"); c("gear", "P")
    time.sleep(2.5)
    shot("s3_passenger_park")
    texts = screen_texts()
    row(3, "동승석 착석, 문 닫기, 기어 P", "그대로 일반 재생, 내 기록 탭 없음",
        last_state() == "NORMAL" and "재생·가사" in texts and "내 기록" not in texts and "설정" not in texts
        and not any("노래방" in x for x in texts), "탭: " + ", ".join(x for x in texts if x in ("재생·가사", "예약 목록", "내 기록", "설정")))

    # 장면 4
    c("seat", "passenger", "off"); c("door", "front_right", "open"); c("door", "front_right", "close")
    t_closed = time.time()
    c("gear", "D")
    t = wait_state("KARAOKE", 12)
    shot("s4_back_to_karaoke")
    waited = (time.time() - t_closed) if t is not None else None
    row(4, "동승석 착석 끄고 문 열었다 닫기, 기어 D", "8초 뒤 다시 노래방",
        waited is not None and 7.5 <= waited <= 9.0, f"문 닫고 {waited:.1f}초" if waited else "")

    # 장면 5
    c("window", "front_left", "30")
    t = wait_state("NORMAL", 2)
    time.sleep(0.4)
    shot("s5a_window_open")
    row("5a", "운전석 창문 30%", "가수 복귀", t is not None, f"{t * 1000:.0f}ms 안에 전이" if t is not None else "")
    c("window", "front_left", "0")
    t_closed = time.time()
    t = wait_state("KARAOKE", 12)
    shot("s5b_window_closed")
    waited = (time.time() - t_closed) if t is not None else None
    row("5b", "창문 다시 닫기", "다시 물러남(들킬 뻔 직후라 8초)", waited is not None and 7.5 <= waited <= 9.0,
        f"닫고 {waited:.1f}초" if waited else "")

    # 장면 8
    time.sleep(12)  # 혼자 부른 곡이 10초 이상 되도록
    c("gear", "P")
    time.sleep(3)
    shot("s8a_parked_summary")
    texts = screen_texts()
    summary = next((x for x in texts if x.startswith("오늘 ")), "")
    row("8a", "기어 P", "오늘 요약, 가사, 4개 탭", bool(summary) and "내 기록" in texts and "설정" in texts, summary)
    tap_text("내 기록")
    time.sleep(1.5)
    shot("s8b_records")
    texts = screen_texts()
    row("8b", "내 기록 탭", "이번 달 혼자 부른 곡·시간, 들킬 뻔 횟수", any(x.startswith("혼자 부른 곡") for x in texts),
        " / ".join(x for x in texts if x.startswith(("혼자 부른", "들킬 뻔", "가장 빠른", "문 ", "창문"))))
    tap_text("재생·가사")
    time.sleep(1)

    with open(os.path.join(OUT, "gate_log.txt"), "w") as f:
        f.write(gate_log())
    md = ["| 장면 | 콘솔·앱에서 한 일 | 기대 | 결과 | 비고 |", "|---|---|---|---|---|"]
    md += [f"| {a} | {b} | {e} | {r} | {n} |" for a, b, e, r, n in rows]
    with open(os.path.join(OUT, "result.md"), "w") as f:
        f.write("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
