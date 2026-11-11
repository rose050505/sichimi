#!/usr/bin/env python3
"""개발용 임시 음원을 코드로 만든다. 제출물에 넣지 않는다.

  tools/make_temp_song.py <번호>       # 1, 2
  tools/make_temp_song.py probe        # G2 측정용 신호음

결과: songs/temp-<번호>/ (mix4.flac, meta.json, lyrics.lrc). `.gitignore`가 이 폴더를 뺀다.
반주는 화음·베이스·하이햇 루프, "보컬"은 비브라토가 있는 멜로디 한 줄이다.
보컬 스템이 줄고 돌아오는 것을 귀로 확인하는 데만 쓴다.
"""
import json
import math
import os
import random
import struct
import subprocess
import sys
import tempfile
import wave

RATE = 44100
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 곡마다 다른 조성과 빠르기
SONGS = {
    "1": dict(title="임시곡 하나", bpm=100, root=57, chords=[[0, 4, 7], [-3, 0, 4], [-7, -3, 0], [-5, -1, 2]],
              melody=[0, 2, 4, 7, 4, 2, 0, None, 4, 5, 7, 9, 7, 5, 4, None]),
    "2": dict(title="임시곡 둘", bpm=120, root=60, chords=[[0, 4, 7], [5, 9, 12], [7, 11, 14], [5, 9, 12]],
              melody=[7, 7, 9, 7, 12, 11, None, None, 7, 7, 9, 7, 14, 12, None, None]),
}


def midi_hz(n):
    return 440.0 * 2 ** ((n - 69) / 12)


def write_wav(path, left, right):
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        frames = bytearray()
        for l, r in zip(left, right):
            frames += struct.pack("<hh", max(-32767, min(32767, int(l * 32767))), max(-32767, min(32767, int(r * 32767))))
        w.writeframes(bytes(frames))


def render(spec, seconds):
    n = int(seconds * RATE)
    beat = 60.0 / spec["bpm"]
    bar = beat * 4
    acc_l = [0.0] * n
    acc_r = [0.0] * n
    voc = [0.0] * n
    rnd = random.Random(7)
    for i in range(n):
        t = i / RATE
        bar_i = int(t / bar) % len(spec["chords"])
        chord = spec["chords"][bar_i]
        tb = t % beat
        # 화음 패드
        pad = sum(math.sin(2 * math.pi * midi_hz(spec["root"] + c) * t) for c in chord) / len(chord)
        pad *= 0.18
        # 베이스(박마다)
        bass_env = math.exp(-tb * 4)
        bass = 0.22 * bass_env * math.sin(2 * math.pi * midi_hz(spec["root"] - 24 + chord[0]) * t)
        # 하이햇(반 박마다)
        th = t % (beat / 2)
        hat = 0.05 * math.exp(-th * 60) * (rnd.random() * 2 - 1)
        acc_l[i] = pad + bass + hat * 0.8
        acc_r[i] = pad * 0.9 + bass + hat
        # 멜로디(반 박 음표)
        step = beat / 2
        k = int(t / step) % len(spec["melody"])
        note = spec["melody"][k]
        if note is not None:
            tn = t % step
            env = min(1.0, tn * 30) * math.exp(-tn * 1.5)
            f = midi_hz(spec["root"] + 12 + note) * (1 + 0.006 * math.sin(2 * math.pi * 5.5 * t))
            ph = 2 * math.pi * f * t
            # 배음을 섞어 사람 목소리처럼 또렷하게
            voc[i] = 0.30 * env * (math.sin(ph) + 0.5 * math.sin(2 * ph) + 0.25 * math.sin(3 * ph) + 0.12 * math.sin(4 * ph)) / 1.87
    return acc_l, acc_r, voc


def lrc(seconds, bpm):
    lines = []
    bar = 60.0 / bpm * 4
    words = ["(임시 가사) 라라라 첫 줄", "(임시 가사) 랄랄라 둘째 줄", "(임시 가사) 다시 한 번", "(임시 가사) 끝까지 불러"]
    t, i = 0.0, 0
    while t < seconds:
        m, s = divmod(t, 60)
        lines.append(f"[{int(m):02d}:{s:05.2f}]{words[i % len(words)]}")
        t += bar * 2
        i += 1
    return "\n".join(lines) + "\n"


def main(num):
    spec = SONGS[num]
    seconds = 64.0
    acc_l, acc_r, voc = render(spec, seconds)
    with tempfile.TemporaryDirectory() as td:
        acc = os.path.join(td, "acc.wav")
        vo = os.path.join(td, "voc.wav")
        write_wav(acc, acc_l, acc_r)
        write_wav(vo, voc, voc)
        sid = f"temp-{num}"
        meta_path = os.path.join(ROOT, "songs", sid, "meta.json")
        if os.path.exists(meta_path):
            os.remove(meta_path)
        subprocess.check_call([sys.executable, os.path.join(ROOT, "tools", "pack_stems.py"), sid, acc, vo,
                               "--title", spec["title"], "--artist", "임시 음원",
                               "--source", "tools/make_temp_song.py로 생성(개발용, 제출하지 않음)", "--license", "-"])
    with open(os.path.join(ROOT, "songs", f"temp-{num}", "lyrics.lrc"), "w", encoding="utf-8") as f:
        f.write(lrc(seconds, spec["bpm"]))


def probe():
    """G2 측정용: 반주는 무음, 보컬은 일정한 880Hz. 출력 크기가 곧 보컬 음량이다."""
    sid = "temp-0-probe"
    with tempfile.TemporaryDirectory() as td:
        acc = os.path.join(td, "acc.wav")
        vo = os.path.join(td, "voc.wav")
        subprocess.check_call(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", f"anullsrc=r={RATE}:cl=stereo", "-t", "900", acc])
        subprocess.check_call(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", f"sine=frequency=880:sample_rate={RATE}:duration=900",
                               "-af", "volume=0.35,pan=stereo|c0=c0|c1=c0", vo])
        meta_path = os.path.join(ROOT, "songs", sid, "meta.json")
        if os.path.exists(meta_path):
            os.remove(meta_path)
        subprocess.check_call([sys.executable, os.path.join(ROOT, "tools", "pack_stems.py"), sid, acc, vo,
                               "--title", "측정용 신호음", "--artist", "임시 음원",
                               "--source", "tools/make_temp_song.py probe(개발용, 제출하지 않음)", "--license", "-"])


if __name__ == "__main__":
    for arg in sys.argv[1:] or ["1", "2"]:
        probe() if arg == "probe" else main(arg)
