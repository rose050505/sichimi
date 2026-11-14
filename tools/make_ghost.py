#!/usr/bin/env python3
"""고스트 대결 시험용 "다른 사람" 녹음을 서버에 올린다(호스트에서 익명 계정 하나를 새로 만든다).

  python3 tools/make_ghost.py <곡 id> <점수> [--shift-ms N]

- 목소리: 원곡 보컬 스템(Demucs 결과)을 7초부터 잘라 16kHz 모노 AAC(ADTS, 32kbps)로 만든다(ffmpeg).
- --shift-ms N: 문서의 startMs를 실제보다 N ms 늦게 적는다. 고스트가 가이드 목소리보다 정확히 N ms 늦게 들려야 하므로,
  들린 소리에서 두 목소리의 간격을 재면 고스트 위치 맞춤의 오차를 알 수 있다(tools/align_ghost.py).
- --voice-from <곡 id>: 다른 곡의 보컬을 목소리로 쓴다(위치 측정용: 가이드 보컬과 섞이지 않아 들린 소리에서 고스트만 찾을 수 있다).
- 별명은 test-delete. 끝에 지울 문서 경로를 찍는다. API 키와 프로젝트 ID는 출력하지 않는다.
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
sys.argv_saved = list(sys.argv)
sys.argv = [sys.argv[0], "server"]
import verify_rules as vr  # noqa: E402

STEMS = os.environ.get("SICHIMI_STEMS_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "음원작업", "분리", "htdemucs"))  # 곡 분리 결과(저장소 밖)
NAMES = {"01-arirang": "arirang_yoon", "02-destroy": "jw_destroy", "03-wade": "jw_wade"}


def main():
    argv = sys.argv_saved
    song, score = argv[1], int(argv[2])
    shift = int(argv[argv.index("--shift-ms") + 1]) if "--shift-ms" in argv else 0
    voice_song = argv[argv.index("--voice-from") + 1] if "--voice-from" in argv else song
    cut_s = 7.0
    with tempfile.TemporaryDirectory() as d:
        aac = os.path.join(d, "g.aac")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", str(cut_s), "-i", os.path.join(STEMS, NAMES[voice_song], "vocals.wav"),
                        "-t", "185", "-ac", "1", "-ar", "16000", "-c:a", "aac", "-b:a", "32k", "-f", "adts", aac], check=True)
        audio = open(aac, "rb").read()
    uid, tok = vr.account("ghost")
    S = f"{vr.BASE}/rank/{song}/scores/{uid}"
    T = f"{vr.BASE}/rank/{song}/takes/{uid}"
    print("점수 문서:", vr.http("PATCH", S, tok, vr.score_doc(nick="test-delete", score=score))[0])
    take = {"fields": {"audio": {"bytesValue": base64.b64encode(audio).decode()}, "score": {"integerValue": str(score)},
                       "startMs": {"integerValue": str(int(cut_s * 1000) + shift)}, "lengthMs": {"integerValue": "185000"},
                       "at": {"timestampValue": vr.now_ts()}}}
    print("녹음 문서:", vr.http("PATCH", T, tok, take)[0], f"{len(audio)}바이트, startMs={int(cut_s * 1000) + shift}(실제보다 {shift}ms 늦게)")
    print(f"지울 시험 문서(콘솔): rank/{song}/scores/{uid}, rank/{song}/takes/{uid}")


if __name__ == "__main__":
    main()
