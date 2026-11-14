#!/usr/bin/env python3
"""관문 G5(마이크)와 노래 점수를 확인한다.

  python3 tools/verify_g5.py <증거 폴더> <경우> [--sec 초] [--lead 초]

경우
  original  원곡 보컬 스템을 가상 마이크로 흘려 넣는다(박자에 맞춰 원곡 가수가 부른 셈) -> 높은 점수
  silent    아무것도 넣지 않는다 -> 0점
  wrong     다른 곡(Destroy)의 보컬을 넣는다 -> 낮은 점수
  door      original과 같되 30초에 동승석 문을 연다 -> 마이크가 꺼지고 그 뒤는 채점하지 않는다
  full      original을 곡 끝까지(--sec 206) -> 완주 점수, 끝나면 음성 한 줄

전제
- 에뮬레이터의 마이크 입력이 PulseAudio 가상 장치 `sichimi_mic`의 모니터에 연결돼 있다(실제 마이크는 쓰지 않는다).
    pactl load-module module-null-sink sink_name=sichimi_mic
    pactl move-source-output <qemu 입력 번호> sichimi_mic.monitor
    adb emu avd hostmicon
- 앱 설정에서 노래 점수가 켜져 있고 마이크 권한이 있다.
이것은 자동 시험이다. 시연 영상에서는 사람이 직접 부른다.
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import verify_scenes as v  # noqa: E402

c, sh = v.c, v.sh
STEMS = os.environ.get("SICHIMI_STEMS_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "음원작업", "분리", "htdemucs"))  # 곡 분리 결과(저장소 밖)
# 믹서에 들어간 소리가 호스트에서 들리기까지(검증기록 G2 실측 395ms). 보컬을 이때 넣어야 "들리는 반주에 맞춰 부르는 사람"과 같다.
# 11/12 G5는 0.25로 넣어 들리는 소리보다 약 0.15초 일렀다(그래서 MIC_LAG_MS가 100으로 잡혔다). 11/14 0.40으로 고침.
# 비교하려면 --lead 0.25처럼 바꿀 수 있다.
OUTPUT_LATENCY_S = float(sys.argv[sys.argv.index("--lead") + 1]) if "--lead" in sys.argv else 0.40


def cabin_alone(gear):
    for seat, x in (("driver", "on"), ("passenger", "off"), ("rear_left", "off"), ("rear_right", "off")):
        c("seat", seat, x)
    for z in ("front_left", "front_right", "rear_left", "rear_right"):
        c("door", z, "close"); c("window", z, "0")
    c("turn", "off"); c("gear", gear)


def set_switch(title, on):
    """주차해서 설정 탭의 스위치(제목 [title])를 원하는 값으로 둔다. 화면에 없으면 아래로 민다."""
    cabin_alone("P")
    sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{v.PKG}/.MainActivity"); time.sleep(3)
    v.dismiss_cards()
    v.tap_text("설정"); time.sleep(1.5)
    for _ in range(4):  # 설정 맨 위로(전에 아래로 내려가 있었을 수 있음)
        sh("adb", "shell", "input", "swipe", "1700", "450", "1700", "1150", "300")
    time.sleep(1)
    for _ in range(8):
        sh("adb", "shell", "uiautomator", "dump", "--user", "10", "/sdcard/ui.xml", check=False)
        xml = sh("adb", "shell", "cat", "/sdcard/ui.xml")
        m = re.search(r'text="%s"[^>]*bounds="\[\d+,(\d+)\]\[\d+,(\d+)\]"' % re.escape(title), xml)
        if m:
            break
        sh("adb", "shell", "input", "swipe", "1700", "1100", "1700", "700", "900"); time.sleep(1)  # 천천히 조금씩(플링으로 건너뛰지 않게)
    else:
        raise SystemExit(f"설정에서 '{title}'을 찾지 못했다")
    y = (int(m.group(1)) + int(m.group(2))) // 2
    sw = re.findall(r'checkable="true" checked="(true|false)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    checked, x1, y1, x2, y2 = min(sw, key=lambda q: abs((int(q[2]) + int(q[4])) // 2 - y))
    if (checked == "true") != on:
        sh("adb", "shell", "input", "tap", str((int(x1) + int(x2)) // 2), str((int(y1) + int(y2)) // 2))
        time.sleep(2)
        # 처음 켤 때 시스템이 마이크 권한을 묻는다(사람이 누르는 것과 같은 버튼을 누른다)
        if v.tap_text("앱 사용 중에만 허용"):
            time.sleep(1.5)


def main():
    out, case = sys.argv[1], sys.argv[2]
    sec = float(sys.argv[sys.argv.index("--sec") + 1]) if "--sec" in sys.argv else 60
    os.makedirs(out, exist_ok=True)
    voice = {"original": "arirang_yoon", "full": "arirang_yoon", "door": "arirang_yoon", "wrong": "jw_destroy"}.get(case)

    set_switch("노래 점수(마이크)", True)
    cabin_alone("D")
    sh("adb", "shell", "am", "force-stop", "--user", "10", v.PKG)
    sh("adb", "logcat", "-c")
    sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{v.PKG}/.MainActivity")
    time.sleep(4)
    log = subprocess.Popen(["adb", "logcat", "-v", "time", "-s", "SC.Mixer:I"], stdout=subprocess.PIPE, text=True)
    v.tap_text("재생") or sh("adb", "shell", "input", "tap", "1693", "812")
    # 곡이 믹서에 들어가기 시작한 줄을 기다린다
    t_start = None
    deadline = time.time() + 10
    for line in log.stdout:
        if "입력" in line:
            t_start = time.time()
            break
        if time.time() > deadline:
            break
    log.terminate()
    if t_start is None:
        raise SystemExit("재생이 시작되지 않았다")
    player = None
    if voice:
        time.sleep(max(0.0, t_start + OUTPUT_LATENCY_S - time.time()))
        player = subprocess.Popen(["paplay", "--device=sichimi_mic", os.path.join(STEMS, voice, "vocals.wav")])
    if case == "door":
        time.sleep(max(0.0, t_start + 30 - time.time()))
        c("door", "front_right", "open")
    time.sleep(max(0.0, t_start + sec - time.time()))
    v.tap_text("다음 곡")  # 앞 곡의 점수를 마무리한다
    time.sleep(2)
    if player:
        player.terminate()
    v.tap_text("일시정지")  # 멈춤
    time.sleep(1)
    logs = sh("adb", "logcat", "-d", "-v", "time", "-s", "SC.Score:I", "SC.Mic:I", "SC.Gate:I", "SC.Tts:I")
    with open(os.path.join(out, f"{case}_log.txt"), "w") as f:
        f.write(logs)
    for l in logs.splitlines():
        if re.search(r"점수|지연 가정|마이크|상태", l):
            print(l)


if __name__ == "__main__":
    main()
