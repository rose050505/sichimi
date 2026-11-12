#!/usr/bin/env python3
"""녹음 듣기 소리에서 목소리와 반주가 맞는지 잰다.

  python3 tools/align_take.py <담은 소리.wav> <곡 id>

담은 소리(에뮬레이터 스피커 출력)를 반주 스템, 보컬 스템과 각각 맞대 본다.
- 반주와 가장 잘 맞는 자리 = 지금 들리는 곡 위치
- 보컬과 가장 잘 맞는 자리 = 녹음된 목소리의 곡 위치(시험에서는 원곡 보컬을 가상 마이크로 넣었으므로)
두 자리의 차이가 "목소리가 반주보다 늦은 ms"다. 0에 가까울수록 노래방처럼 맞는다.
"""
import os
import subprocess
import sys

VENV = os.path.expanduser("~/.venvs/demucs/bin/python")
if sys.executable != VENV and os.path.exists(VENV) and "--inner" not in sys.argv:
    sys.exit(subprocess.call([VENV, "-I", os.path.abspath(__file__), *sys.argv[1:], "--inner"]))

import numpy as np  # noqa: E402
import soundfile as sf  # noqa: E402
from scipy.signal import fftconvolve, resample_poly  # noqa: E402

STEMS = os.path.expanduser("~/해커톤/현대차앱/음원작업/분리/htdemucs")
NAMES = {"01-arirang": "arirang_yoon", "02-destroy": "jw_destroy", "03-wade": "jw_wade"}
SR = 8000


def mono8k(path, start=0.0, dur=None):
    info = sf.info(path)
    a, sr = sf.read(path, start=int(start * info.samplerate),
                    frames=-1 if dur is None else int(dur * info.samplerate), dtype="float32", always_2d=True)
    a = a.mean(axis=1)
    from math import gcd
    g = gcd(SR, sr)
    return resample_poly(a, SR // g, sr // g).astype(np.float32)


def best_lag(x, ref):
    """x가 ref의 어디에 있는가(표본). 정규화 상관 최댓값과 함께."""
    x = x - x.mean()
    c = fftconvolve(ref, x[::-1], mode="valid")
    e = np.sqrt(fftconvolve(ref ** 2, np.ones(len(x), np.float32), mode="valid") * (x ** 2).sum() + 1e-9)
    nc = c / e
    i = int(np.argmax(nc))
    return i, float(nc[i])


def main():
    wav, song = sys.argv[1], sys.argv[2]
    cap = mono8k(wav)
    # 소리가 있는 부분만(앞의 무음은 버림)
    env = np.convolve(np.abs(cap), np.ones(400) / 400, mode="same")
    on = np.where(env > env.max() * 0.05)[0]
    if len(on) == 0:
        print("담은 소리가 비었다"); return
    cap = cap[on[0]:on[-1]]
    d = os.path.join(STEMS, NAMES[song])
    acc = mono8k(os.path.join(d, "no_vocals.wav"))
    voc = mono8k(os.path.join(d, "vocals.wav"))
    # 10초 조각 여러 개로 잰다
    seg = 10 * SR
    rows = []
    for s in range(0, len(cap) - seg, 5 * SR):
        x = cap[s:s + seg]
        ia, ca = best_lag(x, acc)
        iv, cv = best_lag(x, voc)
        rows.append((s / SR, ia / SR, ca, iv / SR, cv, (iv - ia) * 1000 / SR))
    print("담은 소리 %.1f초" % (len(cap) / SR))
    print("조각 시작 | 반주 기준 곡 위치(상관) | 보컬 기준 곡 위치(상관) | 목소리 - 반주")
    for r in rows:
        print("%5.1f초 | %7.3f초 (%.2f) | %7.3f초 (%.2f) | %+6.0f ms" % r)
    good = [r[5] for r in rows if r[2] > 0.3 and r[4] > 0.3]
    if good:
        print("결과: 목소리가 반주보다 %+.0f ms (조각 %d개 중앙값, 범위 %+.0f~%+.0f)" % (np.median(good), len(good), min(good), max(good)))
    else:
        print("결과: 맞대기 상관이 낮아 판정 못 함")


if __name__ == "__main__":
    main()
