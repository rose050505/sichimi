#!/usr/bin/env python3
"""반주 스템과 보컬 스템을 앱이 읽는 한 파일로 묶는다.

  tools/pack_stems.py <곡 id> <반주 파일> <보컬 파일> [--mix2] [--title 제목] [--artist 가수]
                      [--source 출처] [--license 라이선스]

결과: songs/<id>/mix4.flac (채널 0·1 = 반주 좌·우, 2·3 = 보컬 좌·우)
      --mix2이면 songs/<id>/mix2.flac (왼쪽 = 반주 모노, 오른쪽 = 보컬 모노)
      songs/<id>/meta.json (없을 때만 만든다)

두 스템은 길이와 시작점이 같아야 한다. 한 파일에 담기 때문에 재생 중에 어긋나지 않는다.
FLAC 블록 크기를 1024로 작게 잡아 음량 변화가 귀에 닿기까지의 지연을 줄인다.
ffmpeg가 필요하다.
"""
import argparse
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def duration_ms(path):
    out = subprocess.check_output([
        "ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", path,
    ]).decode().strip()
    return int(float(out) * 1000)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("song_id")
    ap.add_argument("accompaniment")
    ap.add_argument("vocal")
    ap.add_argument("--mix2", action="store_true", help="2채널(반주 모노 + 보컬 모노)로 만든다")
    ap.add_argument("--title", default="")
    ap.add_argument("--artist", default="")
    ap.add_argument("--source", default="")
    ap.add_argument("--license", default="")
    ap.add_argument("--rate", type=int, default=44100)
    a = ap.parse_args()

    out_dir = os.path.join(ROOT, "songs", a.song_id)
    os.makedirs(out_dir, exist_ok=True)
    if a.mix2:
        out = os.path.join(out_dir, "mix2.flac")
        graph = ("[0:a]aformat=sample_rates={r},pan=mono|c0=0.5*c0+0.5*c1[acc];"
                 "[1:a]aformat=sample_rates={r},pan=mono|c0=0.5*c0+0.5*c1[voc];"
                 "[acc][voc]join=inputs=2:channel_layout=stereo:map=0.0-FL|1.0-FR[a]").format(r=a.rate)
    else:
        out = os.path.join(out_dir, "mix4.flac")
        graph = ("[0:a]aformat=sample_rates={r}:channel_layouts=stereo[acc];"
                 "[1:a]aformat=sample_rates={r}:channel_layouts=stereo[voc];"
                 "[acc][voc]join=inputs=2:channel_layout=quad:map=0.0-FL|0.1-FR|1.0-BL|1.1-BR[a]").format(r=a.rate)
    subprocess.check_call([
        "ffmpeg", "-y", "-v", "error", "-i", a.accompaniment, "-i", a.vocal,
        "-filter_complex", graph, "-map", "[a]",
        "-c:a", "flac", "-sample_fmt", "s16", "-frame_size", "1024", "-compression_level", "8", out,
    ])

    meta_path = os.path.join(out_dir, "meta.json")
    if not os.path.exists(meta_path):
        meta = {
            "title": a.title or a.song_id,
            "artist": a.artist,
            "durationMs": duration_ms(out),
            "source": a.source,
            "license": a.license,
        }
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
            f.write("\n")
    print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
