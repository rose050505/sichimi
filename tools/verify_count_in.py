#!/usr/bin/env python3
"""박자 세기(카운트인)를 가상 콘솔 신호로 확인한다. 첫 곡(01-arirang)을 처음부터 세 번 틀고 호스트 소리를 녹음한다.

  ~/.venvs/demucs/bin/python tools/verify_count_in.py docs/evidence/count_in [--reuse]

- 가: 운전석만, 문 닫힘, 기어 D, 설정 켬  -> 노래방에서 첫 소절 앞 딸깍 네 번이 들려야 한다
- 나: 동승석 문 열림(노래방 아님)          -> 딸깍이 없어야 한다
- 다: 가와 같은데 설정에서 끔               -> 딸깍이 없어야 한다
세 녹음의 첫 소절 앞(보컬이 아직 없는 곳)은 반주만 같으므로, 녹음끼리 시각을 맞춘 뒤 빼면 딸깍만 남는다.
앱 안의 가짜 입력은 쓰지 않는다. 설정은 주차(P) 때 설정 탭의 스위치를 눌러 바꾼다.
"""
import json
import os
import re
import subprocess
import sys
import time

import numpy as np
import soundfile as sf
from scipy.signal import butter, sosfiltfilt

sys.path.insert(0, os.path.dirname(__file__))
import verify_scenes as v  # noqa: E402

c, sh = v.c, v.sh
OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/evidence/count_in"
SONG = "01-arirang"
REC_SEC = 24


def cabin_alone(gear):
    for seat, x in (("driver", "on"), ("passenger", "off"), ("rear_left", "off"), ("rear_right", "off")):
        c("seat", seat, x)
    for z in ("front_left", "front_right", "rear_left", "rear_right"):
        c("door", z, "close"); c("window", z, "0")
    c("turn", "off"); c("gear", gear)


def set_count_in(on):
    """주차해서 설정 탭의 스위치를 눌러 원하는 값으로 둔다."""
    cabin_alone("P")
    sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{v.PKG}/.MainActivity"); time.sleep(3)
    v.tap_text("설정"); time.sleep(1.5)
    sh("adb", "shell", "uiautomator", "dump", "--user", "10", "/sdcard/ui.xml", check=False)
    xml = sh("adb", "shell", "cat", "/sdcard/ui.xml")
    m = re.search(r'text="첫 소절 박자 세기"[^>]*bounds="\[\d+,(\d+)\]\[\d+,(\d+)\]"', xml)
    sw = re.findall(r'checkable="true" checked="(true|false)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m or not sw:
        raise SystemExit("설정 화면에서 박자 세기 스위치를 찾지 못했다")
    y = (int(m.group(1)) + int(m.group(2))) // 2
    checked, x1, y1, x2, y2 = min(sw, key=lambda s: abs((int(s[2]) + int(s[4])) // 2 - y))
    if (checked == "true") != on:
        sh("adb", "shell", "input", "tap", str((int(x1) + int(x2)) // 2), str((int(y1) + int(y2)) // 2))
        time.sleep(1)
    v.shot(f"settings_count_in_{'on' if on else 'off'}")


def run(name, passenger_door_open):
    cabin_alone("D")
    if passenger_door_open:
        c("door", "front_right", "open")
    sh("adb", "shell", "am", "force-stop", "--user", "10", v.PKG)
    sh("adb", "logcat", "-c")
    sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{v.PKG}/.MainActivity"); time.sleep(4)
    path = os.path.join(OUT, f"{name}.wav")
    rec = subprocess.Popen(["parecord", "--format=s16le", "--rate=48000", "--channels=2", "--device=@DEFAULT_MONITOR@", path])
    time.sleep(0.5)
    v.tap_text("재생") or sh("adb", "shell", "input", "tap", "1693", "812")
    time.sleep(REC_SEC)
    rec.terminate(); rec.wait()
    sh("adb", "shell", "input", "tap", "1693", "812")  # 멈춤
    log = sh("adb", "logcat", "-d", "-s", "SC.Gate:I", "SC.Mixer:I")
    with open(os.path.join(OUT, f"{name}_log.txt"), "w") as f:
        f.write(log)
    return path, log


def load(path):
    x, sr = sf.read(path)
    return x.mean(axis=1), sr


def accompaniment(sr, sec):
    """곡 파일의 반주(앞 두 채널)를 모노로 푼다."""
    raw = subprocess.check_output(["ffmpeg", "-v", "error", "-i", os.path.join("songs", SONG, "mix5.ogg"), "-t", str(sec),
                                   "-filter_complex", "pan=mono|c0=0.5*FL+0.5*FR", "-ar", str(sr), "-f", "f32le", "-"])
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def song_start(x, ref):
    """녹음에서 곡 0초가 놓인 표본 위치(반주와 상호상관). 곡은 녹음을 시작하고 몇 초 안에 시작하므로 앞 8초에서만 찾는다
    (곡 안에서 되풀이되는 마디에 잘못 맞는 것을 막는다)."""
    n = len(x) + len(ref)
    f = np.fft.irfft(np.fft.rfft(x, n) * np.conj(np.fft.rfft(ref, n)), n)
    return int(np.argmax(f[: 8 * 48000]))


def clicks_in(diff, t0, sr):
    """두 녹음의 차이에서 딸깍 시각을 찾는다. 에뮬레이터 소리 경로가 표본 단위로 똑같지는 않아 반주가 조금 남으므로
    박자 소리(드럼 스틱, 에너지 중심 2.4kHz) 대역만 걸러 본다. 소리가 있으면 바닥의 20배 안팎, 없으면 6배 아래였다."""
    diff = sosfiltfilt(butter(4, [1500, 4500], btype="band", fs=sr, output="sos"), diff)
    hop = int(0.01 * sr)
    env = np.array([np.sqrt(np.mean(diff[i:i + hop] ** 2)) for i in range(0, len(diff) - hop, hop)])
    floor = np.median(env) + 1e-12
    found = []
    for i in range(1, len(env) - 1):
        if env[i] > 10 * floor and env[i] >= env[i - 1] and env[i] >= env[i + 1]:
            if not found or i - found[-1] > 15:
                found.append(i)
    return [t0 + i * 0.01 for i in found], env.max() / floor


def main():
    os.makedirs(OUT, exist_ok=True)
    meta = json.load(open(os.path.join("songs", SONG, "meta.json"), encoding="utf-8"))
    clicks = np.array(meta["countIn"]["clicksMs"]) / 1000
    vocal_at = meta["countIn"]["vocalAtMs"] / 1000

    if "--reuse" in sys.argv:  # 이미 받은 녹음으로 분석만 다시 한다
        a, b, cc = (os.path.join(OUT, f"{n}.wav") for n in ("a_karaoke_on", "b_door_open", "c_karaoke_off"))
        log_a, log_b, log_c = (open(p[:-4] + "_log.txt").read() for p in (a, b, cc))
    else:
        set_count_in(True)
        a, log_a = run("a_karaoke_on", False)
        b, log_b = run("b_door_open", True)
        set_count_in(False)
        cc, log_c = run("c_karaoke_off", False)
        set_count_in(True)  # 기본값으로 돌려 둔다

    xa, sr = load(a); xb, _ = load(b); xc, _ = load(cc)
    ref = accompaniment(sr, 12)
    sa, sb, sc = (song_start(x, ref) for x in (xa, xb, xc))
    t0, t1 = clicks[0] - 1.0, vocal_at - 0.05  # 보컬이 들어오기 전: 세 녹음의 차이는 딸깍뿐이다

    def seg(x, s0):
        return x[s0 + int(t0 * sr): s0 + int(t1 * sr)]

    pa, ra = clicks_in(seg(xa, sa) - seg(xc, sc), t0, sr)  # 가 - 다
    pb, rb = clicks_in(seg(xb, sb) - seg(xc, sc), t0, sr)  # 나 - 다
    karaoke_a = "ARMING -> KARAOKE" in log_a
    karaoke_c = "ARMING -> KARAOKE" in log_c
    exp = ", ".join(f"{t:.2f}" for t in clicks)
    err = [abs(p - e) * 1000 for p, e in zip(pa, clicks)]
    ok_a = karaoke_a and len(pa) == 4 and max(err) <= 30
    ok_b = "-> KARAOKE" not in log_b and len(pb) == 0
    rows = [
        ("가", "혼자, 기어 D, 설정 켬", f"노래방에서 첫 소절 앞 딸깍 4번(곡 {exp}초)", ok_a,
         f"가-다 차이에서 찾은 딸깍 곡 {', '.join(f'{t:.2f}' for t in pa)}초(어긋남 최대 {max(err) if err else 0:.0f}ms, 크기 바닥의 {ra:.0f}배)"),
        ("나", "동승석 문 열림", "노래방이 아님, 딸깍 없음", ok_b,
         f"상태 로그에 노래방 없음. 나-다 차이에서 찾은 딸깍 {len(pb)}개(최대가 바닥의 {rb:.1f}배)"),
        ("다", "혼자, 기어 D, 설정 끔", "노래방이어도 딸깍 없음", karaoke_c and ok_a and ok_b,
         "노래방이 됨(상태 로그). 가에서는 다와의 차이로 딸깍 4번이, 나에서는 0번이 나오므로 다에는 딸깍이 없다"),
    ]
    with open(os.path.join(OUT, "result.md"), "w", encoding="utf-8") as f:
        f.write("곡 시작은 녹음과 곡 반주의 상호상관으로 맞췄다. 시각은 곡 안의 시각이다.\n\n")
        f.write("| 경우 | 차 상태 | 기대 | 결과 | 비고 |\n|---|---|---|---|---|\n")
        for r in rows:
            f.write(f"| {r[0]} | {r[1]} | {r[2]} | {'통과' if r[3] else '**실패**'} | {r[4]} |\n")
    for r in rows:
        print(r[0], "통과" if r[3] else "실패", r[4])


if __name__ == "__main__":
    main()
