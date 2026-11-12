#!/usr/bin/env python3
"""가사 파일(lyrics.lrc)의 줄 시각을 보컬 스템에 강제 정렬로 다시 맞춘다. 가사 글자는 그대로 두고 시각만 바꾼다.

  ~/.venvs/demucs/bin/python tools/align_lrc.py <보컬 wav> <lyrics.lrc> [--out 새 lrc] [--from 초]

- 받아쓰기(make_lrc.py)의 단어 시각은 노래에서 1초 넘게 늦을 때가 있다. 그래서 가사를 이미 알고 있는 상태에서
  소리와 글자를 맞추는 강제 정렬(MMS 1130개 언어 정렬 모델 + torchaudio forced_align, CTC)로 각 줄이 시작하는 때를 찾는다.
- 한국어는 한글을 로마자로 바꿔 정렬한다(모델이 로마자만 안다). 줄 사이에는 별표(*) 토큰을 넣어
  가사 파일에 없는 소리(추임새, 반복)를 흡수한다.
- 노래에서는 정렬도 틀릴 때가 있다(가사 파일에 없는 노래가 길면 몇 초씩 끌려간다). 그래서 정렬이 원래 시각과
  2초 넘게 다르면 원래 시각을 두고, 마지막에 보컬이 쉬었다가 다시 시작하는 곳(구 시작)에 맞춘다.
  아리랑에서 따로 확인한 기준점(첫 줄 16.6초, 셋째 줄 31.2초, 넷째 줄 38.5초, "이 고갤" 188.8초, "아리 아라리요" 193.1초)과 맞는다.
- --from: 가사가 시작하기 전 구간을 건너뛴다(정렬을 흔들지 않게).
모델은 Hugging Face `MahmoudAshraf/mms-300m-1130-forced-aligner`(Meta MMS, CC BY-NC 4.0, safetensors)다.
torchaudio의 MMS_FA는 받는 곳(dl.fbaipublicfiles.com)의 인증서가 만료돼(2026-11-12) 쓰지 않는다.
모델은 만들 때만 쓰고 앱에는 들어가지 않는다. 결과물은 줄 시각(숫자)뿐이다.
"""
import argparse
import json
import re
import subprocess

import numpy as np
from huggingface_hub import hf_hub_download
import torch
import torchaudio
from transformers import Wav2Vec2ForCTC

MODEL = "MahmoudAshraf/mms-300m-1130-forced-aligner"

CHO = ["g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"]
JUNG = ["a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i"]
JONG = ["", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t"]


def romanize(text):
    parts = []
    for ch in text:
        c = ord(ch)
        if 0xAC00 <= c <= 0xD7A3:
            c -= 0xAC00
            parts.append(CHO[c // 588] + JUNG[(c % 588) // 28] + JONG[c % 28])
        else:
            parts.append(ch.lower())
    return "".join(parts)


def words_of(line, labels):
    ws = []
    for w in romanize(line).split():
        w = "".join(ch for ch in w if ch in labels and ch not in "-*")
        if w:
            ws.append(w)
    return ws


def load16k(path):
    raw = subprocess.check_output(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", "16000", "-f", "f32le", "-"])
    return torch.from_numpy(np.frombuffer(raw, dtype=np.float32).copy())


def stamp(sec):
    m, s = divmod(max(0.0, sec), 60)
    return f"[{int(m):02d}:{s:05.2f}]"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("vocal")
    ap.add_argument("lrc")
    ap.add_argument("--out")
    ap.add_argument("--from", dest="start", type=float, default=0.0)
    ap.add_argument("--star-penalty", type=float, default=3.0, help="별표 열을 글자 최댓값보다 이만큼(로그 확률) 낮춘다")
    a = ap.parse_args()

    lines = []
    for raw in open(a.lrc, encoding="utf-8"):
        m = re.match(r"\[(\d+):(\d+(?:\.\d+)?)\](.*)", raw.strip())
        if m:
            lines.append((int(m.group(1)) * 60 + float(m.group(2)), m.group(3).strip()))

    model = Wav2Vec2ForCTC.from_pretrained(MODEL, use_safetensors=True).eval()
    vocab = json.load(open(hf_hub_download(MODEL, "vocab.json"), encoding="utf-8"))
    dictionary = {k: v for k, v in vocab.items() if len(k) == 1}
    labels = set(dictionary)
    star = len(vocab)  # 별표: 어떤 글자든 받아 주는 열(글자 열들의 최댓값)
    dictionary["*"] = star

    wav = load16k(a.vocal)[int(a.start * 16000):]
    wav = (wav - wav.mean()) / (wav.std() + 1e-7)
    chunks = []
    with torch.inference_mode():
        step = 30 * 16000  # 30초씩(겹침 1초)으로 나눠 메모리를 아낀다
        for s0 in range(0, wav.shape[0], step):
            seg = wav[max(0, s0 - 16000): s0 + step + 16000]
            lp = torch.log_softmax(model(seg.unsqueeze(0)).logits[0], dim=-1)
            fps = lp.shape[0] / (seg.shape[0] / 16000)
            lead = int(round((s0 - max(0, s0 - 16000)) / 16000 * fps))
            keep = int(round(min(step, wav.shape[0] - s0) / 16000 * fps))
            chunks.append(lp[lead: lead + keep])
    emission = torch.cat(chunks)
    emission = torch.cat([emission, emission[:, 4:].max(dim=-1, keepdim=True).values - a.star_penalty], dim=-1).unsqueeze(0)
    frame_sec = wav.shape[0] / 16000 / emission.shape[1]

    # 줄마다 원래 시각 앞 3초~뒤 2초 안에서만 정렬한다. 한 번에 전체를 정렬하면 가사 파일에 없는 노래(반복, 추임새)에
    # 줄이 끌려가 몇 초씩 틀린다(아리랑 끝부분에서 확인).
    # 1) 전체를 한 번에 정렬한다.
    tokens, line_first = [], []
    for _, text in lines:
        tokens.append(star)
        line_first.append(len(tokens))
        tokens.extend(dictionary[ch] for w in words_of(text, labels) for ch in w)
    tokens.append(star)
    aligned, scores = torchaudio.functional.forced_align(emission, torch.tensor([tokens], dtype=torch.int32), blank=0)
    spans = torchaudio.functional.merge_tokens(aligned[0], scores[0].exp())

    # 2) 보컬이 0.25초 넘게 쉬었다가 다시 나는 곳(구 시작).
    vocal = load16k(a.vocal).numpy()
    hop = 160
    rms = np.sqrt(np.convolve(vocal ** 2, np.ones(hop) / hop, mode="valid")[::hop])
    on = rms > 0.15 * np.percentile(rms, 95)
    phrase, quiet = [], 0
    for i, v in enumerate(on):
        if v:
            if quiet >= 25:
                phrase.append(i * 0.01)
            quiet = 0
        else:
            quiet += 1
    phrase = np.array(phrase)

    # 3) 정렬이 원래 시각과 2초 안이면 정렬을, 아니면 원래 시각을 기준으로 삼고,
    #    기준 바로 앞 1.5초(뒤 0.3초) 안에 구 시작이 있으면 그리로 당긴다.
    result = []
    for (old, text), i in zip(lines, line_first):
        al = a.start + spans[i].start * frame_sec if i < len(spans) else old
        base = al if abs(al - old) <= 2.0 else old
        near = phrase[(phrase >= base - 1.5) & (phrase <= base + 0.3)]
        new = float(near[-1]) if len(near) else base
        why = ("정렬" if base == al else "원래") + ("+구 시작" if len(near) else "")
        result.append((new, text))
        print(f"{old:7.2f} -> {new:7.2f} ({new - old:+.2f}) [{why}, 정렬 {al:.2f}]  {text}")
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write("\n".join(f"{stamp(t)}{text}" for t, text in result) + "\n")


if __name__ == "__main__":
    main()
