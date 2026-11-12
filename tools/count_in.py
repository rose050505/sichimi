#!/usr/bin/env python3
"""첫 소절 앞 박자 세기(카운트인) 딸깍 소리 트랙을 만든다.

  ~/.venvs/demucs/bin/python tools/count_in.py <반주 wav> <보컬 wav> <lyrics.lrc> <출력 click.wav> [--meta songs/<id>/meta.json]

1. 반주에서 박자를 찾는다(librosa beat_track).
2. 첫 소절이 들어가는 때를 찾는다. 가사 파일의 첫 줄 시각 근처에서 보컬 스템 소리가 처음 커지는 곳.
3. 그때와 가장 가까운 박을 "들어가는 박"으로 보고, 바로 앞 네 박에 딸깍 소리를 넣는다(첫 박은 조금 높게).
4. 곡과 길이가 같은 모노 wav로 쓴다. tools/pack_stems.py --click 으로 5채널 파일에 넣는다.
--meta를 주면 meta.json에 "countIn"(빠르기, 첫 소절 시각, 딸깍 시각)을 적는다. 앱은 읽지 않는 기록용이다.

librosa(ISC)는 만들 때만 쓰고 앱에는 들어가지 않는다.
"""
import argparse
import json

import librosa
import numpy as np
import soundfile as sf

BEATS = 4
CLICK_PEAK = 0.32  # 약 -10dBFS


def first_vocal_onset(vocal, sr, lrc_sec):
    """가사 첫 줄 시각 앞뒤에서 보컬 소리가 처음 커지는 시각(초)."""
    hop = int(sr * 0.01)
    rms = librosa.feature.rms(y=vocal, frame_length=hop * 4, hop_length=hop)[0]
    loud = np.percentile(rms, 95)
    lo, hi = int((lrc_sec - 1.5) / 0.01), int((lrc_sec + 0.7) / 0.01)
    lo = max(lo, 0)
    for i in range(lo, min(hi, len(rms))):
        if rms[i] > 0.15 * loud:
            return i * 0.01
    return lrc_sec


def click(sr, hz, ms=35):
    t = np.arange(int(sr * ms / 1000)) / sr
    return np.sin(2 * np.pi * hz * t) * np.exp(-t * 90) + 0.5 * np.sin(2 * np.pi * hz * 2.01 * t) * np.exp(-t * 140)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("accompaniment")
    ap.add_argument("vocal")
    ap.add_argument("lrc")
    ap.add_argument("out")
    ap.add_argument("--meta")
    a = ap.parse_args()

    acc, sr = sf.read(a.accompaniment, always_2d=True)
    voc, sr2 = sf.read(a.vocal, always_2d=True)
    assert sr == sr2, "두 스템의 표본율이 같아야 한다"
    acc_m, voc_m = acc.mean(axis=1), voc.mean(axis=1)

    first_line = min(
        int(m) * 60 + float(s)
        for m, s in (l[1:9].split(":") for l in open(a.lrc, encoding="utf-8") if l.startswith("["))
    )
    onset = first_vocal_onset(voc_m.astype(np.float32), sr, first_line)

    tempo, beats = librosa.beat.beat_track(y=acc_m.astype(np.float32), sr=sr, units="time")
    tempo = float(np.atleast_1d(tempo)[0])
    k = int(np.argmin(np.abs(beats - onset)))
    period = float(np.median(np.diff(beats)))
    if k < BEATS:
        raise SystemExit(f"첫 소절({onset:.2f}초) 앞에 박이 {k}개뿐이다")
    clicks = beats[k - BEATS:k]
    off = (onset - beats[k]) / period

    track = np.zeros(len(acc_m))
    for i, t in enumerate(clicks):
        c = click(sr, 2000 if i == 0 else 1500) * CLICK_PEAK
        s = int(round(t * sr))
        track[s:s + len(c)] += c[: len(track) - s]
    sf.write(a.out, track.astype(np.float32), sr, subtype="PCM_16")

    print(f"빠르기 {tempo:.1f}BPM(박 간격 {period * 1000:.0f}ms), 가사 첫 줄 {first_line:.2f}초, 보컬 시작 {onset:.2f}초, "
          f"들어가는 박 {beats[k]:.2f}초(보컬이 {off:+.2f}박), 딸깍 {', '.join(f'{t:.2f}' for t in clicks)}초")
    if abs(off) > 0.35:
        print("주의: 보컬 시작이 박에서 많이 떨어져 있다. 귀로 확인할 것")

    if a.meta:
        with open(a.meta, encoding="utf-8") as f:
            meta = json.load(f)
        meta["countIn"] = {
            "bpm": round(tempo, 1),
            "vocalAtMs": int(round(onset * 1000)),
            "clicksMs": [int(round(t * 1000)) for t in clicks],
        }
        with open(a.meta, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
            f.write("\n")


if __name__ == "__main__":
    main()
