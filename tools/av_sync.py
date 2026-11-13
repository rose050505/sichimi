#!/usr/bin/env python3
"""녹음(qemu 출력)과 원곡 음원(songs/<id>/mix5.ogg: 반주·박자·보컬)을 맞춰 보는 도구(11/13, 시연 영상 확인용).

- song_offset: 녹음의 어느 시각이 곡 0초인지(반주와 교차 상관). 앱 로그의 재생 위치와 비교하면 "앱이 그 위치를 낸 때 → 소리가 녹음에 담긴 때"
  지연이 나온다(= 화면과 소리 사이의 실제 틈).
- vocal_ratio: 짧은 창마다 녹음 ≈ a·반주 + v·보컬 + c·박자를 최소제곱으로 풀어 v/a(보컬 음량 비)를 구한다. 노래방 0.2, 평소 1.0.

  ~/.venvs/demucs/bin/python -I tools/av_sync.py <녹음 wav> <곡 id> <곡 0초의 대략 녹음 시각(초)>
"""
import os
import subprocess
import sys

import numpy as np
import soundfile as sf

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SR = 48000


def load_song(song_id, cache_dir="/tmp"):
    src = os.path.join(ROOT, "songs", song_id, "mix5.ogg")
    wav = os.path.join(os.environ.get("TMPDIR", cache_dir), f"{song_id}_mix5.wav")
    if not os.path.exists(wav):
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src, "-ar", str(SR), "-c:a", "pcm_f32le", wav], check=True)
    x, sr = sf.read(wav, dtype="float32")
    assert sr == SR and x.shape[1] == 5
    acc = x[:, 0:2].mean(axis=1)
    click = x[:, 2]
    voc = x[:, 3:5].mean(axis=1)
    return acc, voc, click


def load_rec(path):
    x, sr = sf.read(path, dtype="float32")
    assert sr == SR
    return x.mean(axis=1) if x.ndim > 1 else x


def song_offset(rec, acc, approx, search=1.5, at_song=None, win=8.0):
    """녹음 시각 = 곡 시각 + offset. approx 근처 ±search초에서 반주와 교차 상관이 가장 큰 곳."""
    s0 = at_song if at_song is not None else max(0.0, -approx) + 1.0
    a = acc[int(s0 * SR):int((s0 + win) * SR)]
    r0 = int((s0 + approx - search) * SR)
    r = rec[max(0, r0):int((s0 + approx + win + search) * SR)]
    n = 1 << int(np.ceil(np.log2(len(r) + len(a))))
    cc = np.fft.irfft(np.fft.rfft(r, n) * np.conj(np.fft.rfft(a, n)), n)[:len(r) - len(a)]
    k = int(np.argmax(cc))
    return (max(0, r0) + k) / SR - s0, float(cc[k] / (np.linalg.norm(a) * np.linalg.norm(r[k:k + len(a)]) + 1e-12))


def vocal_ratio(rec, acc, voc, click, offset, t0, t1, win=0.08, hop=0.01):
    """녹음 시각 t0~t1에서 보컬/반주 음량 비. 돌려주는 시각은 녹음 시각(초)."""
    out_t, out_v = [], []
    w, h = int(win * SR), int(hop * SR)
    for i in range(int(t0 * SR), int(t1 * SR) - w, h):
        j = i - int(offset * SR)
        if j < 0 or j + w > len(acc):
            continue
        y = rec[i:i + w]
        X = np.stack([acc[j:j + w], voc[j:j + w], click[j:j + w]], axis=1)
        if np.abs(X[:, 1]).max() < 1e-3:          # 보컬이 없는 곳
            continue
        coef, *_ = np.linalg.lstsq(X, y, rcond=None)
        if coef[0] <= 1e-6:
            continue
        out_t.append((i + w / 2) / SR)
        out_v.append(coef[1] / coef[0])
    return np.array(out_t), np.array(out_v)


def ratio_step(t, v, t_hint, before=(-1.0, -0.2), after=(0.4, 1.2)):
    """t_hint 근처에서 보컬 비가 올라간 시각(앞뒤 가운데 값의 중간을 처음 넘는 때)."""
    b = np.median(v[(t >= t_hint + before[0]) & (t < t_hint + before[1])])
    a = np.median(v[(t >= t_hint + after[0]) & (t < t_hint + after[1])])
    m = (t >= t_hint + before[1]) & (t <= t_hint + after[1])
    tt, vv = t[m], v[m]
    mid = (a + b) / 2
    sm = np.convolve(vv, np.ones(5) / 5, mode="same")
    idx = np.where(sm >= mid)[0] if a > b else np.where(sm <= mid)[0]
    return (float(tt[idx[0]]) if len(idx) else None), float(b), float(a)


if __name__ == "__main__":
    if len(sys.argv) != 4 or sys.argv[1].startswith("-"):
        print(__doc__)
        sys.exit(2)
    rec = load_rec(sys.argv[1])
    acc, voc, click = load_song(sys.argv[2])
    off, score = song_offset(rec, acc, float(sys.argv[3]))
    print(f"곡 0초 = 녹음 {off:.3f}초 (상관 {score:.2f})")
