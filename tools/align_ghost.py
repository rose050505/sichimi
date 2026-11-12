#!/usr/bin/env python3
"""고스트 대결 소리에서 고스트 목소리가 제자리에 깔리는지 잰다(make_ghost.py --shift-ms N으로 올린 고스트용).

  python3 tools/align_ghost.py <담은 소리.wav> <곡 id> <N> [--voice-from <곡 id>]

--voice-from: 고스트 목소리가 다른 곡의 보컬이면(make_ghost.py --voice-from) 그 보컬과 맞댄다. 가이드와 섞이지 않아 정확하다.

담은 소리 = 반주 + 가이드 보컬(원곡, 곡과 표본 단위로 맞음) + 고스트(같은 보컬을 N ms 늦게 적어 올린 것).
반주로 곡 위치를 찾고, 그 자리 앞뒤로 보컬 스템과 맞대 상관 곡선을 그린다. 0 근처 봉우리 = 가이드, N 근처 봉우리 = 고스트.
고스트 봉우리 위치 - N = 고스트 위치 맞춤의 오차(+면 고스트가 늦음).
"""
import os
import subprocess
import sys

VENV = os.path.expanduser("~/.venvs/demucs/bin/python")
if sys.executable != VENV and os.path.exists(VENV) and "--inner" not in sys.argv:
    sys.exit(subprocess.call([VENV, "-I", os.path.abspath(__file__), *sys.argv[1:], "--inner"]))

import numpy as np  # noqa: E402
from scipy.signal import fftconvolve  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from align_take import mono8k, best_lag, STEMS, NAMES, SR  # noqa: E402


def main():
    wav, song, shift = sys.argv[1], sys.argv[2], int(sys.argv[3])
    cap = mono8k(wav)
    env = np.convolve(np.abs(cap), np.ones(400) / 400, mode="same")
    on = np.where(env > env.max() * 0.05)[0]
    cap = cap[on[0]:on[-1]]
    d = os.path.join(STEMS, NAMES[song])
    acc = mono8k(os.path.join(d, "no_vocals.wav"))
    vf = sys.argv[sys.argv.index("--voice-from") + 1] if "--voice-from" in sys.argv else song
    voc = mono8k(os.path.join(STEMS, NAMES[vf], "vocals.wav"))
    seg = 10 * SR
    pre, post = int(0.3 * SR), int((shift / 1000 + 0.6) * SR)
    print("조각 | 곡 위치 | 가이드 봉우리 | 고스트 봉우리 | 고스트 오차")
    errs = []
    for s in range(0, len(cap) - seg, 5 * SR):
        x = cap[s:s + seg]; x = x - x.mean()
        ia, ca = best_lag(x, acc)
        lo = ia - pre
        if lo < 0 or ia + post + seg > len(voc):
            continue
        ref = voc[lo:ia + post + seg]
        c = fftconvolve(ref, x[::-1], mode="valid")
        e = np.sqrt(fftconvolve(ref ** 2, np.ones(len(x), np.float32), mode="valid") * (x ** 2).sum() + 1e-9)
        nc = c / e
        lags_ms = (np.arange(len(nc)) - pre) * 1000 / SR
        g0 = (lags_ms > -150) & (lags_ms < 150)
        g1 = (lags_ms > shift - 250) & (lags_ms < shift + 250)
        p0 = lags_ms[g0][np.argmax(nc[g0])]; v0 = nc[g0].max()
        p1 = lags_ms[g1][np.argmax(nc[g1])]; v1 = nc[g1].max()
        print(f"{s / SR:5.1f}초 | {ia / SR:6.2f}초({ca:.2f}) | {p0:+5.0f}ms({v0:.2f}) | {p1:+5.0f}ms({v1:.2f}) | {p1 - shift:+5.0f}ms")
        if ca > 0.3 and v1 > 0.005:
            errs.append(p1 - shift)
    if errs:
        print(f"결과: 고스트가 제자리보다 {np.median(errs):+.0f} ms (조각 {len(errs)}개 중앙값, 범위 {min(errs):+.0f}~{max(errs):+.0f})")
    else:
        print("결과: 고스트 봉우리를 찾지 못함")


if __name__ == "__main__":
    main()
