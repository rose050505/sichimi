#!/usr/bin/env python3
"""분리한 보컬 스템을 받아쓰기(faster-whisper)해 시각이 붙은 가사 파일(lyrics.lrc)을 만든다.

  ~/.venvs/demucs/bin/python tools/make_lrc.py <보컬 wav> <언어 ko|en> <출력 lrc> [--model medium]

- 자동 받아쓰기라 틀린 곳이 있을 수 있다. 만든 뒤 사람이 고쳐 쓰는 초안이다.
- faster-whisper(MIT)와 Whisper 모델(MIT)은 만들 때만 쓰고 앱에는 들어가지 않는다.
"""
import argparse
import subprocess

import numpy as np
from faster_whisper import WhisperModel


def load16k(path):
    """ffmpeg로 16kHz 모노 float32로 읽는다(faster-whisper의 PyAV 디코더를 쓰지 않는다)."""
    raw = subprocess.check_output(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", "16000", "-f", "f32le", "-"])
    return np.frombuffer(raw, dtype=np.float32)


def stamp(sec):
    m, s = divmod(max(0.0, sec), 60)
    return f"[{int(m):02d}:{s:05.2f}]"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("vocal")
    ap.add_argument("lang")
    ap.add_argument("out")
    ap.add_argument("--model", default="medium")
    ap.add_argument("--no-vad", action="store_true", help="노래에서 VAD가 소리를 잘라 먹을 때")
    a = ap.parse_args()
    model = WhisperModel(a.model, device="cpu", compute_type="int8")
    segments, _ = model.transcribe(load16k(a.vocal), language=a.lang, vad_filter=not a.no_vad, beam_size=5,
                                   condition_on_previous_text=False, word_timestamps=True)
    lines = []
    for seg in segments:
        text = seg.text.strip()
        if text:
            start = seg.words[0].start if seg.words else seg.start
            lines.append(f"{stamp(start)}{text}")
            print(lines[-1], flush=True)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
