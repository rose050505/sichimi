#!/usr/bin/env python3
"""노래 점수의 기준: 원곡 보컬 스템의 음높이 흐름(pitch.u8)을 만든다.

  ~/.venvs/demucs/bin/python tools/make_pitch.py <보컬 wav> songs/<id>

- librosa pyin으로 20ms마다 음높이를 찾는다(65~1050Hz).
- 점수에 쓸 "음표 프레임"만 남긴다: pyin이 목소리로 본 프레임 중 소리가 충분히 크고(곡 상위 5% 크기의 10% 이상),
  앞뒤 2프레임(100ms) 동안 음높이가 0.8반음 안에서 머무는 곳. 랩·숨소리·미끄러지는 소리는 0(채점 안 함)이 된다.
  (목소리 확률 0.6 이상만 쓰면 아리랑에서 5%만 남아 너무 적었다.)
- 파일: 한 프레임에 한 바이트. 0 = 채점 안 함, 그 밖 = MIDI 음 번호 × 2(반음의 절반 단위, 예 69번 A4 = 138).
- meta.json에 "pitch": {"hopMs", "frames", "noteFrames"}를 적는다.
"""
import json
import os
import subprocess
import sys

import librosa
import numpy as np

SR = 16000
HOP_MS = 20


def load16k(path):
    raw = subprocess.check_output(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"])
    return np.frombuffer(raw, dtype=np.float32)


def main():
    vocal, song_dir = sys.argv[1], sys.argv[2]
    y = load16k(vocal)
    hop = SR * HOP_MS // 1000
    f0, voiced, prob = librosa.pyin(y, fmin=65, fmax=1050, sr=SR, frame_length=1024, hop_length=hop, center=True)
    midi = np.where(np.isfinite(f0), librosa.hz_to_midi(np.nan_to_num(f0, nan=1.0)), np.nan)
    rms = librosa.feature.rms(y=y, frame_length=1024, hop_length=hop, center=True)[0][: len(midi)]
    ok = voiced & np.isfinite(midi) & (rms > 0.1 * np.percentile(rms, 95))
    stable = np.zeros_like(ok)
    for i in range(2, len(midi) - 2):
        w = midi[i - 2:i + 3]
        if ok[i - 2:i + 3].all() and np.nanmax(w) - np.nanmin(w) <= 0.8:
            stable[i] = True
    out = np.zeros(len(midi), dtype=np.uint8)
    out[stable] = np.clip(np.round(midi[stable] * 2), 1, 255).astype(np.uint8)
    out.tofile(os.path.join(song_dir, "pitch.u8"))

    meta_path = os.path.join(song_dir, "meta.json")
    meta = json.load(open(meta_path, encoding="utf-8"))
    meta["pitch"] = {"hopMs": HOP_MS, "frames": int(len(out)), "noteFrames": int(stable.sum())}
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
        f.write("\n")
    notes = midi[stable]
    print(f"{song_dir}: {len(out)}프레임 중 음표 {stable.sum()}개({stable.mean() * 100:.0f}%), "
          f"음역 {librosa.midi_to_note(np.percentile(notes, 5))}~{librosa.midi_to_note(np.percentile(notes, 95))}")


if __name__ == "__main__":
    main()
