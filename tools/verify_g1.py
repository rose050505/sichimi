#!/usr/bin/env python3
"""관문 G1: 가상 콘솔에서 바꾼 값이 앱 로그(SC.Signal)에 얼마 만에 찍히는지 잰다.

전제: 에뮬레이터 실행, `adb forward tcp:8088 tcp:8088`, 콘솔 하트비트(DDS ONLINE), 앱 실행 중.
출력: 표(마크다운)와 원본 로그를 지정한 폴더에 쓴다.

  tools/verify_g1.py docs/evidence/g1
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import console  # noqa: E402

# (설명, 콘솔 명령, 로그에서 기다릴 패턴)
STEPS = [
    ("동승석 착석 켜기", ("seat", "passenger", "on"), r"좌석 FRONT_RIGHT callback"),
    ("동승석 착석 끄기", ("seat", "passenger", "off"), r"좌석 FRONT_RIGHT callback"),
    ("운전석 착석 켜기", ("seat", "driver", "on"), r"좌석 FRONT_LEFT callback"),
    ("뒷좌석 왼쪽 착석 켜기", ("seat", "rear_left", "on"), r"좌석 REAR_LEFT callback"),
    ("뒷좌석 왼쪽 착석 끄기", ("seat", "rear_left", "off"), r"좌석 REAR_LEFT callback"),
    ("뒷좌석 오른쪽 착석 켜기", ("seat", "rear_right", "on"), r"좌석 REAR_RIGHT callback"),
    ("뒷좌석 오른쪽 착석 끄기", ("seat", "rear_right", "off"), r"좌석 REAR_RIGHT callback"),
    ("동승석 문 열기", ("door", "front_right", "open"), r"문 FRONT_RIGHT callback"),
    ("동승석 문 닫기", ("door", "front_right", "close"), r"문 FRONT_RIGHT callback"),
    ("운전석 문 열기", ("door", "front_left", "open"), r"문 FRONT_LEFT callback"),
    ("운전석 문 닫기", ("door", "front_left", "close"), r"문 FRONT_LEFT callback"),
    ("뒷문 왼쪽 열기", ("door", "rear_left", "open"), r"문 REAR_LEFT callback"),
    ("뒷문 왼쪽 닫기", ("door", "rear_left", "close"), r"문 REAR_LEFT callback"),
    ("뒷문 오른쪽 열기", ("door", "rear_right", "open"), r"문 REAR_RIGHT callback"),
    ("뒷문 오른쪽 닫기", ("door", "rear_right", "close"), r"문 REAR_RIGHT callback"),
    ("운전석 창문 30%", ("window", "front_left", "30"), r"창문 FRONT_LEFT callback"),
    ("운전석 창문 2%", ("window", "front_left", "2"), r"창문 FRONT_LEFT callback"),
    ("운전석 창문 3%", ("window", "front_left", "3"), r"창문 FRONT_LEFT callback"),
    ("운전석 창문 100%", ("window", "front_left", "100"), r"창문 FRONT_LEFT callback"),
    ("운전석 창문 0%", ("window", "front_left", "0"), r"창문 FRONT_LEFT callback"),
    ("뒷창문 오른쪽 50%", ("window", "rear_right", "50"), r"창문 REAR_RIGHT callback"),
    ("뒷창문 오른쪽 0%", ("window", "rear_right", "0"), r"창문 REAR_RIGHT callback"),
    ("기어 D", ("gear", "D"), r"기어\("),
    ("기어 R", ("gear", "R"), r"기어\("),
    ("기어 N", ("gear", "N"), r"기어\("),
    ("기어 P", ("gear", "P"), r"기어\("),
    ("방향지시등 왼쪽", ("turn", "left"), r"방향지시등 callback"),
    ("방향지시등 끄기", ("turn", "off"), r"방향지시등 callback"),
]

LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+MB\.Signal:\s(.*)$")


def guest_offset():
    """호스트 시각 - 게스트 시각(초). adb 왕복 시간의 절반을 보정한다."""
    best = None
    for _ in range(5):
        t0 = time.time()
        g = float(subprocess.check_output(["adb", "shell", "date", "+%s.%N"]).decode().strip())
        t1 = time.time()
        rtt = t1 - t0
        off = (t0 + t1) / 2 - g
        if best is None or rtt < best[0]:
            best = (rtt, off)
    return best[1], best[0]


def main(outdir):
    os.makedirs(outdir, exist_ok=True)
    subprocess.run(["adb", "logcat", "-c"], check=True)
    logf = open(os.path.join(outdir, "logcat_SC.Signal.txt"), "w")
    proc = subprocess.Popen(["adb", "logcat", "-v", "epoch", "-s", "SC.Signal:I"], stdout=logf, stderr=subprocess.DEVNULL)
    time.sleep(1.0)
    offset, rtt = guest_offset()
    rows = []
    try:
        for name, cmd, pattern in STEPS:
            t_host = time.time()
            console.main(list(cmd))
            deadline = t_host + 3.0
            hit = None
            while time.time() < deadline and hit is None:
                time.sleep(0.05)
                logf.flush()
                with open(logf.name) as f:
                    for line in f:
                        m = LINE.match(line)
                        if not m:
                            continue
                        t_guest = float(m.group(1)) + offset
                        if t_guest >= t_host - 0.02 and re.search(pattern, m.group(2)):
                            hit = (t_guest, m.group(2))
                            break
            if hit:
                rows.append((name, " ".join(cmd), f"{(hit[0] - t_host) * 1000:.0f}", hit[1]))
            else:
                rows.append((name, " ".join(cmd), "없음(3초 초과)", ""))
            time.sleep(0.6)
    finally:
        proc.terminate()
        logf.close()

    md = [f"게스트-호스트 시계 차 보정값 {offset * 1000:.1f}ms(adb 왕복 {rtt * 1000:.1f}ms 기준).",
          "지연 = 콘솔에 PUT을 보낸 호스트 시각부터 앱 로그 줄의 시각까지.", "",
          "| 콘솔에서 한 일 | 명령 | 지연(ms) | 앱 로그 |", "|---|---|---|---|"]
    for r in rows:
        md.append(f"| {r[0]} | `{r[1]}` | {r[2]} | {r[3]} |")
    out = "\n".join(md) + "\n"
    with open(os.path.join(outdir, "latency.md"), "w") as f:
        f.write(out)
    print(out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "docs/evidence/g1")
