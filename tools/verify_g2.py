#!/usr/bin/env python3
"""관문 G2: 가상 콘솔 신호에서 귀에 들리는 소리 변화까지의 시간과 딸깍 소리를 잰다.

전제
- 에뮬레이터 실행, `adb forward tcp:8088 tcp:8088`, 콘솔 하트비트(DDS ONLINE)
- 앱이 측정용 신호음(`songs/temp-0-probe`, 반주 무음 + 보컬 880Hz)을 재생 중이고
  콘솔이 장면 1 조건(운전석만 착석, 문·창문 닫힘, 기어 D)이라 노래방 상태일 것
- 호스트가 PipeWire/PulseAudio이고 에뮬레이터 소리가 기본 출력으로 나올 것

하는 일
1. 기본 출력의 모니터를 녹음한다. 받은 조각마다 호스트 시각을 붙여 표본 번호를 호스트 시각으로 바꾼다.
2. 동승석 문 열기를 콘솔에 보낸 호스트 시각 T0를 적고, 1.5초 뒤 문을 닫고, 노래방이 다시 열릴 때까지 기다린다. N번 되풀이.
3. 녹음의 5ms 단위 크기에서, T0 뒤 소리가 커지기 시작한 때(10%), 절반(50%), 다 올라온 때(90%)를 찾는다.
4. 딸깍 소리: 880Hz 사인파의 표본 간 차이 최댓값보다 크게 튀는 곳을 센다. 끊김: 재생 중 크기가 0으로 떨어진 구간을 센다.

  tools/verify_g2.py docs/evidence/g2 [반복 횟수]
"""
import array
import math
import os
import re
import subprocess
import sys
import threading
import time
import wave

sys.path.insert(0, os.path.dirname(__file__))
import console  # noqa: E402

RATE = 48000
WIN = 240  # 5ms


class Capture:
    def __init__(self):
        self.proc = subprocess.Popen(
            ["parecord", "--raw", "--format=s16le", f"--rate={RATE}", "--channels=2", "--latency-msec=5",
             "--device=@DEFAULT_MONITOR@"], stdout=subprocess.PIPE)
        self.chunks = []  # (도착 시각, 바이트)
        self.t = threading.Thread(target=self._run, daemon=True)
        self.t.start()

    def _run(self):
        while True:
            b = self.proc.stdout.read1(4096) if hasattr(self.proc.stdout, "read1") else self.proc.stdout.read(4096)
            if not b:
                break
            self.chunks.append((time.time(), b))

    def stop(self):
        self.proc.terminate()
        self.t.join(timeout=2)
        data = b"".join(c[1] for c in self.chunks)
        # 표본 번호 -> 호스트 시각: 조각의 끝 표본이 도착한 시각의 하한선
        frames = 0
        offset = None
        for arrived, b in self.chunks:
            frames += len(b) // 4
            o = arrived - frames / RATE
            offset = o if offset is None else min(offset, o)
        return data, offset


def mono(data):
    a = array.array("h")
    a.frombytes(data[: len(data) // 4 * 4])
    return [(a[i] + a[i + 1]) / 2 for i in range(0, len(a), 2)]


def rms_windows(x):
    return [math.sqrt(sum(v * v for v in x[i:i + WIN]) / WIN) for i in range(0, len(x) - WIN, WIN)]


def last_state():
    """SC.Gate 로그의 마지막 상태 줄에서 지금 상태를 읽는다."""
    out = subprocess.run(["adb", "logcat", "-d", "-s", "SC.Gate:I"], capture_output=True, text=True).stdout
    states = re.findall(r"상태 \w+ -> (\w+)", out)
    return states[-1] if states else None


def wait_state(name, timeout):
    end = time.time() + timeout
    while time.time() < end:
        if last_state() == name:
            return True
        time.sleep(0.2)
    return False


def main(outdir, n):
    os.makedirs(outdir, exist_ok=True)
    cap = Capture()
    time.sleep(1.0)
    events = []
    for k in range(n):
        if not wait_state("KARAOKE", 15):
            print("노래방 상태가 아니다. 조건을 확인할 것", file=sys.stderr)
            break
        time.sleep(1.5)
        t0 = time.time()
        console.main(["door", "front_right", "open"])
        events.append(t0)
        time.sleep(1.5)
        console.main(["door", "front_right", "close"])
    time.sleep(1.0)
    data, offset = cap.stop()
    with wave.open(os.path.join(outdir, "capture.wav"), "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(data)

    x = mono(data)
    r = rms_windows(x)
    loud = sorted(r)
    high = loud[int(len(loud) * 0.95)]
    rows = []
    for t0 in events:
        i0 = int((t0 - offset) * RATE / WIN)
        before = r[max(0, i0 - 40):i0]
        low = sorted(before)[len(before) // 2] if before else 0
        span = high - low
        found = {}
        for frac in (0.1, 0.5, 0.9):
            for j in range(i0, min(len(r), i0 + 400)):
                if r[j] >= low + frac * span:
                    found[frac] = (j - i0) * WIN / RATE * 1000
                    break
        rows.append((low / high if high else 0, found.get(0.1), found.get(0.5), found.get(0.9)))

    # 딸깍 소리와 끊김
    amp = high * math.sqrt(2)
    max_step = amp * 2 * math.pi * 880 / RATE
    clicks = 0
    for i in range(1, len(x)):
        if abs(x[i] - x[i - 1]) > max_step * 1.6 + 200:
            clicks += 1
    playing = [v for v in r if v > high * 0.05]
    gaps = sum(1 for i in range(1, len(r) - 1) if r[i] < high * 0.02 and r[i - 1] > high * 0.1)

    md = ["녹음: 호스트 기본 출력의 모니터, 48kHz. 크기는 5ms 창의 RMS.",
          f"평소 크기(상위 5%) = {high:.0f}. 사인파 표본 간 최대 차이 기대값 = {max_step:.0f}.", "",
          "| # | 노래방일 때 크기(평소 대비) | 커지기 시작(10%) ms | 절반(50%) ms | 다 올라옴(90%) ms |", "|---|---|---|---|---|"]
    for i, (lo, a, b, c) in enumerate(rows, 1):
        f = lambda v: "-" if v is None else f"{v:.0f}"
        md.append(f"| {i} | {lo:.2f} | {f(a)} | {f(b)} | {f(c)} |")
    md += ["", f"딸깍 소리 후보(기대 최대 차이의 1.6배를 넘는 표본 간 점프): {clicks}개",
           f"끊김 후보(재생 중 크기가 2% 아래로 떨어진 창): {gaps}개, 재생 창 {len(playing)}개"]
    out = "\n".join(md) + "\n"
    with open(os.path.join(outdir, "result.md"), "w") as fp:
        fp.write(out)
    print(out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "docs/evidence/g2", int(sys.argv[2]) if len(sys.argv) > 2 else 5)
