#!/usr/bin/env python3
"""가사 싱크 확인 영상(record_demo.py lyrics)에서 가사 줄이 바뀐 순간과 그때 들린 노래 위치를 잰다(11/13).

- 줄 바뀜: 에뮬레이터 화면 조각의 가사 칸(1920x1080 녹화 기준 x 600~1900, y 440~610)에서 앞 프레임과 크게 달라진 프레임.
  화면 조각의 호스트 시각은 compose_demo.calibrate(문지기 전이 로그와 화면 변화)로 맞춘다.
- 들린 위치: 녹음(qemu 출력)과 원곡 반주를 교차 상관해 녹음 시각 ↔ 곡 시각을 구한다(av_sync.song_offset, 여러 곳에서 재어 흔들림도 본다).
- 비교: 가사 파일(lyrics.lrc) 시각. 가사 파일 시각은 11/12에 보컬 시작에 맞춘 값이다(검증기록 「가사 줄 시각 맞춤」).

  ~/.venvs/demucs/bin/python -I tools/check_lyrics_sync.py recordings/demo_lyrics 01-arirang [결과 md]
"""
import json
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import av_sync as A  # noqa: E402
import compose_demo as C  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def lrc(song):
    out = []
    for line in open(os.path.join(ROOT, "songs", song, "lyrics.lrc"), encoding="utf-8"):
        m = re.match(r"\[(\d+):(\d+\.\d+)\](.*)", line.strip())
        if m:
            out.append((int(m.group(1)) * 60 + float(m.group(2)), m.group(3)))
    return out


def vocal_onset(voc, t, span=1.0):
    """줄 시각 t 앞뒤 span초에서 보컬 줄기가 처음 커진 때(10ms 창, 그 구간 최대의 −20dB을 처음 넘는 곳)."""
    sr = A.SR
    a, b = int((t - span) * sr), int((t + span) * sr)
    seg = voc[max(0, a):b]
    hop = sr // 100
    env = np.sqrt(np.convolve(seg ** 2, np.ones(hop) / hop, mode="same")[::hop] + 1e-12)
    db = 20 * np.log10(env)
    idx = np.where(db >= db.max() - 20)[0]
    return max(0, a) / sr + idx[0] / 100 if len(idx) else None


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    if len(args) < 2 or any(a.startswith("-") for a in sys.argv[1:]):
        print(__doc__)
        sys.exit(2)
    rec_dir, song = args[0], args[1]
    out_md = args[2] if len(args) > 2 else os.path.join(rec_dir, "lyrics_sync.md")
    meta = json.load(open(os.path.join(rec_dir, "events.json")))
    events, segs, t_audio = meta["events"], meta["segs"], meta["t_audio"]
    log = C.load_log(os.path.join(rec_dir, "logcat.txt"))
    changes = C.gate_changes(log)
    seg = segs[0]
    mp4 = os.path.join(rec_dir, seg["name"] + ".mp4")
    zero, found, _ = C.calibrate(seg, mp4, changes)
    p0 = next(e["t"] for e in events if e["kind"] == "part_start")
    p1 = next(e["t"] for e in events if e["kind"] == "part_end")

    # 녹음 시각 = 곡 시각 + off. 재생을 누른 때를 곡 0초로 어림하고 여러 곳에서 잰다.
    rec = A.load_rec(os.path.join(rec_dir, "audio.wav"))
    acc, voc, clk = A.load_song(song)
    t_play = next(e["t"] for e in events if e["kind"] == "app" and "처음부터" in e["text"])
    approx = t_play - t_audio
    offs = []
    for at in (8.0, 20.0, 32.0, 44.0):
        if at + 4 + approx < len(rec) / A.SR - 1:
            o, sc = A.song_offset(rec, acc, approx + 0.5, search=2.0, at_song=at, win=4.0)
            offs.append((at, o, sc))
    off = float(np.median([o for _, o, _ in offs]))

    # 화면: 가사 칸의 프레임 차이
    pts = np.array([float(x) for x in C.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                             "frame=pts_time", "-of", "csv=p=0", mp4]).split() if x.strip()])
    raw = C.run(["ffmpeg", "-v", "error", "-i", mp4, "-vf", "crop=1300:170:600:440,scale=650:85,format=gray",
                 "-fps_mode", "passthrough", "-f", "rawvideo", "-"], binary=True)
    arr = np.frombuffer(raw, np.uint8).reshape(-1, 85, 650).astype(np.int16)
    n = min(len(pts), len(arr))
    pts, arr = pts[:n], arr[:n]
    d = np.zeros(n)
    d[1:] = np.abs(arr[1:] - arr[:-1]).mean(axis=(1, 2))
    gate_t = [g for g, a, b in changes]
    spikes = []
    for i in np.where(d > 3.0)[0]:
        h = zero + pts[i]
        if any(abs(h - g) < 0.5 for g in gate_t):       # 무대가 처음 나타난 때 등 화면 전체가 바뀐 프레임은 뺀다
            continue
        if not (p0 <= h <= p1) or (spikes and h - spikes[-1][0] < 0.4):
            continue
        spikes.append((h, float(d[i])))

    lines = lrc(song)
    rows = []
    for h, dv in spikes:
        heard = (h - t_audio) - off                      # 그 순간 녹음에서 들린 곡 위치
        lt, text = min(lines, key=lambda x: abs(x[0] - heard))
        if abs(heard - lt) > 3.0:                        # 탭 누르기 등 가사 줄이 아닌 화면 변화
            continue
        on = vocal_onset(voc, lt)
        rows.append({"video_s": round(h - p0, 2), "heard_song_s": round(heard, 2), "lrc_s": lt, "line": text,
                     "vs_lrc_s": round(heard - lt, 2), "vocal_onset_s": None if on is None else round(on, 2),
                     "vs_vocal_s": None if on is None else round(heard - on, 2), "diff": round(dv, 1)})
    # 박자 소리(곡 14.79·15.26·15.72·16.17초)가 녹음의 몇 초에 들렸는지(영상 시각)
    clicks = [round(c / 1000 + off - (p0 - t_audio), 2) for c in (14791, 15256, 15720, 16173)]

    md = ["| 영상 시각 | 바뀐 줄 | 가사 파일 시각 | 줄이 바뀐 순간 들린 곡 위치 | 차이 |", "|---|---|---|---|---|"]
    for r in rows:
        md.append(f"| {r['video_s']:.2f}초 | {r['line']} | {r['lrc_s']:.2f}초 | {r['heard_song_s']:.2f}초 | {r['vs_lrc_s']:+.2f}초 |")
    md.append("")
    fits = ", ".join("%s→%s 나머지 %dms" % (f["from"], f["to"], f["resid_ms"]) for f in found)
    md.append(f"- 화면 조각 맞춤: {fits}")
    md.append("- 녹음 시각 ↔ 곡 시각(반주 상관): " + ", ".join(f"곡 {a:.0f}초 근처 {o:.3f}초(상관 {s:.2f})" for a, o, s in offs)
              + f" → 가운데 값 {off:.3f}초")
    md.append(f"- 박자 소리 4번이 들리는 영상 시각: {', '.join(f'{c:.2f}' for c in clicks)}초")
    md.append(f"- 차이가 −이면 줄이 노래보다 먼저 바뀐다. 영상의 소리는 에뮬레이터 출력 지연(약 0.4초)을 그대로 담았다.")
    open(out_md, "w", encoding="utf-8").write("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
