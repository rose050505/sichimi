#!/usr/bin/env python3
"""장면 7(설계 8절, 2순위): 혼자만의 노래방 중에 내비에 남은 시간 3~5분 목적지를 넣으면
"도착까지 N분, 한 곡 더 됩니다"를 TTS로 한 번 말한다(같은 목적지가 3·4·5분으로 바뀌어 나온다).

차 신호는 가상 콘솔 HTTP로, 목적지는 에뮬레이터 내비 화면을 사람이 누르듯 탭해서 넣는다(앱 안 가짜 입력 없음).
목적지: 내비 검색 → ("최근" 목록, 없으면 음식점 분류) → "더몰트하우스 서핑시티"(음식점 분류 6번, 목록 거리 320m).
에뮬레이터 데이터 초기화(11/12 22:40) 뒤 기본 위치(성남시 수정구 시흥동, 판교 근처)에서 경로 330m, 3분(11/13에 세 번 재어 모두 3분).
초기화 전에는 "탐앤탐스 세종연구소입구점"(1.0km, 3~5분)을 썼는데 지금은 목록에 없다. 도착 시험(verify_arrival.py)의
"스타벅스 판교아이스퀘어점"(2분)과는 다른 곳이다. 목록의 다른 곳은 2분 또는 5분이라 3~5분 구간 안쪽인 3분을 골랐다.
호스트 소리를 parecord로 함께 담는다(docs/evidence/**/*.wav는 저장소에 넣지 않는다).

  tools/verify_scene7.py docs/evidence/scene7      # 앱 데이터를 지우고(pm clear) 시작한다
  tools/verify_scene7.py --help                    # 사용법만 찍는다(시험·pm clear 없음)
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import verify_scenes as vs  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/evidence/scene7"
vs.OUT = OUT
DEST = "더몰트하우스 서핑시티"


def tts_log():
    return vs.sh("adb", "logcat", "-d", "-v", "time", "-s", "SC.Tts:I", "SC.Navi:I")


def wait_log(pattern, timeout):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if re.search(pattern, tts_log()):
            return time.time() - t0
        time.sleep(0.3)
    return None


def dump():
    vs.sh("adb", "shell", "uiautomator", "dump", "--user", "10", "/sdcard/ui.xml", check=False)
    return vs.sh("adb", "shell", "cat", "/sdcard/ui.xml")


def tap(x, y, wait=1.5):
    vs.sh("adb", "shell", "input", "tap", str(x), str(y))
    time.sleep(wait)


def bounds_of(text, xml=None):
    m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(text), xml or dump())
    return tuple(map(int, m.groups())) if m else None


NAVI = "ai.umos.maps.android.navigation.app/.MainActivity"


def open_navi(wait=3):
    """내비를 앞으로 가져온다. 독의 아이콘은 내비가 이미 앞에 있으면 앞 앱으로 되돌아가서 쓰지 않는다."""
    vs.sh("adb", "shell", "am", "start", "--user", "10", "-n", NAVI, check=False)
    time.sleep(wait)


def reset_navi():
    """앞 시험이 남긴 경로·화면을 없애려고 내비를 한 번 다시 시작한다(시험 준비. 앱 쪽 입력이 아니다)."""
    vs.sh("adb", "shell", "am", "force-stop", "--user", "10", "ai.umos.maps.android.navigation.app")
    t0 = time.time()
    while time.time() - t0 < 60:
        open_navi(5)
        if bounds_of("어디로 갈까요?"):
            return True
    return False


def set_destination():
    """내비 화면에서 목적지를 고른다. 화면 좌표는 2560x1440 기준."""
    open_navi(3)
    for _ in range(5):          # 앞 시험이 남긴 경로·검색·상세 화면을 닫고 지도 첫 화면으로
        xml = dump()
        if bounds_of("어디로 갈까요?", xml):
            break
        b = bounds_of("안내 종료", xml)
        if b:
            tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, 3)
            continue
        vs.sh("adb", "shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.5)
    b = bounds_of("어디로 갈까요?")
    if not b:
        return False
    tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, 2)
    b = bounds_of(DEST)         # 한 번 가 본 곳이면 "최근" 목록에 있다
    if b:
        tap(1400, (b[1] + b[3]) // 2 + 8, 0)
        return True
    tap(1445, 266, 4)           # 음식점 분류(분류 줄의 넷째 칸)
    for _ in range(5):
        b = bounds_of(DEST)
        if b:
            tap(1400, (b[1] + b[3]) // 2 + 8, 0)   # 그 줄의 길 안내 버튼
            return True
        vs.sh("adb", "shell", "input", "swipe", "1160", "1100", "1160", "450", "300")
        time.sleep(1.5)
    return False


def main():
    os.makedirs(OUT, exist_ok=True)
    rows = []

    def row(scene, did, expect, ok, note=""):
        rows.append((scene, did, expect, "통과" if ok else "**실패**", note))
        print(scene, did, "->", "통과" if ok else "실패", note)

    navi_ok = reset_navi()
    print("내비 첫 화면:", navi_ok)
    vs.sh("adb", "shell", "pm", "clear", "--user", "10", vs.PKG, check=False)
    for seat, v in (("driver", "on"), ("passenger", "off"), ("rear_left", "off"), ("rear_right", "off")):
        vs.c("seat", seat, v)
    for z in ("front_left", "front_right", "rear_left", "rear_right"):
        vs.c("door", z, "close"); vs.c("window", z, "0")
    vs.c("turn", "off"); vs.c("gear", "D")
    vs.sh("adb", "logcat", "-c")
    vs.sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{vs.PKG}/.MainActivity")
    time.sleep(4)
    tap(1693, 812, 0)           # 주행 화면의 재생 버튼
    t = vs.wait_state("KARAOKE", 12)
    row("7-0", "운전석만 착석, 문·창문 닫힘, 기어 D에서 재생", "노래방", t is not None, f"재생 후 {t:.1f}초" if t else "")

    wav = os.path.join(OUT, "capture.wav")
    rec = subprocess.Popen(["timeout", "40", "parecord", "--format=s16le", "--rate=48000", "--channels=2",
                            "--device=@DEFAULT_MONITOR@", wav], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    t_rec = time.time()
    vs.sh("adb", "logcat", "-c")   # 앞에서 받은 남은 시간과 섞이지 않게
    ok = set_destination()
    t_dest = time.time()
    remain = wait_log(r"남은 시간 \S+ 정보: [1-9]\d*초", 15)
    log = tts_log()
    secs = re.findall(r"남은 시간 \S+ 정보: ([1-9]\d*)초", log)
    row("7a", f"내비 목적지 '{DEST}'", "앱이 남은 시간을 받음", ok and remain is not None and bool(secs),
        f"남은 시간 {secs[-1]}초, 경로 시작 뒤 {remain:.1f}초에 수신" if secs and remain is not None else "목적지 못 고름" if not ok else "")
    said = wait_log(r"말하기 끝", 20)
    time.sleep(0.5)
    vs.shot("s7_navi_route")
    log = tts_log()
    req = re.findall(r"말하기 요청: (.+)", log)
    row("7b", "노래방 + 남은 시간 3~5분", "'도착까지 N분, 한 곡 더 됩니다'를 한 번 말함",
        said is not None and len(req) == 1 and re.fullmatch(r"도착까지 [345]분, 한 곡 더 됩니다", req[0]) is not None,
        (f"요청 {len(req)}번: {req[-1] if req else ''}, 목적지 넣고 {said:.1f}초 뒤 말하기 끝") if said is not None else "말하기 끝 없음")
    time.sleep(max(0, 20 - (time.time() - t_rec)))
    rec.terminate()
    rec.wait()

    tap(1216, 1224, 3)          # 안내 종료
    vs.sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{vs.PKG}/.MainActivity")
    time.sleep(2)
    log = tts_log()
    row("7c", "안내 종료", "남은 시간 없음으로 돌아감", "경로 취소: 경로 없음" in log or "도착" in log, "")

    with open(os.path.join(OUT, "tts_navi_log.txt"), "w") as f:
        f.write(log)
    with open(os.path.join(OUT, "times.txt"), "w") as f:
        f.write(f"녹음 시작 0.0초, 목적지 누름 {t_dest - t_rec:.1f}초\n")
    md = ["| 장면 | 콘솔·앱에서 한 일 | 기대 | 결과 | 비고 |", "|---|---|---|---|---|"]
    md += [f"| {a} | {b} | {e} | {r} | {n} |" for a, b, e, r, n in rows]
    with open(os.path.join(OUT, "result.md"), "w") as f:
        f.write("\n".join(md) + "\n")
    print("\n".join(md))


USAGE = """사용법: tools/verify_scene7.py [결과 폴더]   (기본 docs/evidence/scene7)
  장면 7 시험을 돈다. 시작할 때 앱 데이터를 지운다(pm clear --user 10 app.sichimi).
  -로 시작하는 인자(--help, -h 등)를 주면 이 안내만 찍고 아무것도 하지 않는다."""


def check_args(argv):
    """시험을 돌려도 되는 인자인지 본다. 아니면 사용법을 찍고 끝낸다(pm clear 전에)."""
    if any(a.startswith("-") for a in argv[1:]) or len(argv) > 2:
        print(USAGE)
        sys.exit(0 if all(a in ("-h", "--help") for a in argv[1:]) else 2)


if __name__ == "__main__":
    check_args(sys.argv)
    main()
