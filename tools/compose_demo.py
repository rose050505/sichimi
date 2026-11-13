#!/usr/bin/env python3
"""record_demo.py가 담은 것(에뮬레이터 화면 조각, qemu 소리, 콘솔 탭 프레임, 이벤트)을 한 영상으로 합친다(11/13).

  ~/.venvs/demucs/bin/python -I tools/compose_demo.py recordings/demo_main docs/media/demo_main.mp4 [--layout main|lyrics] [--check-only] [--keep-gaps]

맞추기
- 소리(audio.wav)와 콘솔 프레임은 호스트 시계로 찍혀 있다(parecord를 띄운 시각, CDP 프레임 시각).
- adb screenrecord 조각은 첫 프레임의 호스트 시각을 모른다. 문지기 로그(logcat -v epoch)의 상태 전이 시각 + 그리기 30ms에
  화면이 크게 바뀐 프레임을 겹쳐 조각의 시작 시각을 정한다(전이가 여러 번이면 가운데 값, 나머지로 어긋남을 본다).
- 확인: 가수 음량이 올라가는 전이(→ NORMAL, → RETURN) 뒤 소리가 커진 시각을 재서 로그 시각과의 차이(지연)를 적는다.
  이 에뮬레이터의 출력 지연은 약 0.23초 + 호스트 전달 약 0.15초(검증기록 G2)라 0.2~0.5초면 맞게 담긴 것이다.
  지연은 실제 차에서 보이는 그대로 두고 영상에서 당기지 않는다.
결과: 영상, 그리고 <녹화 폴더>/sync.json(맞춘 값과 확인 값), <녹화 폴더>/parts/(조각별 영상과 자막).
H.264(libx264 -preset veryfast -crf 20), AAC 192k, 1920x1080 30fps.

- 소리의 무음 틈(에뮬레이터가 바빠 qemu 출력에 끼는 수 ms~수십 ms의 0)은 시각을 그대로 두고 바로 앞 소리로 메운다(conceal_gaps, 11/14).
  메운 자리는 sync.json의 audio_gaps. --keep-gaps면 그대로 둔다.
"""
import json
import os
import re
import subprocess
import sys

import numpy as np
import soundfile as sf

# 화면이 크게 바뀌는 전이만 맞춤에 쓴다(NORMAL↔ARMING은 화면이 거의 그대로, RETURN→KARAOKE는 앱이 뒤에 있을 때가 많다)
VISUAL = {("KARAOKE", "NORMAL"), ("ARMING", "KARAOKE"), ("KARAOKE", "RETURN")}
RENDER = 0.03                    # 상태 전이 로그 → 화면에 그려지기까지(한두 프레임)
FONT = "Noto Sans CJK KR"


def run(cmd, binary=False):
    r = subprocess.run(cmd, capture_output=True, check=True)
    return r.stdout if binary else r.stdout.decode()


def load_log(path):
    rows = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.match(r"\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+(SC\.\w+)\s*:\s*(.*)$", line)
        if m:
            rows.append((float(m.group(1)), m.group(2), m.group(3).strip()))
    return rows


def gate_changes(log):
    out = []
    for t, tag, msg in log:
        m = re.match(r"상태 (\w+) -> (\w+)", msg)
        if tag == "SC.Gate" and m:
            out.append((t, m.group(1), m.group(2)))
    return out


def frames(mp4, w=192, h=108):
    pts = [float(x) for x in run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                  "frame=pts_time", "-of", "csv=p=0", mp4]).split() if x.strip()]
    raw = run(["ffmpeg", "-v", "error", "-i", mp4, "-vf", f"scale={w}:{h},format=gray", "-fps_mode", "passthrough",
               "-f", "rawvideo", "-"], binary=True)
    arr = np.frombuffer(raw, np.uint8).reshape(-1, h, w).astype(np.int16)
    n = min(len(pts), len(arr))
    return np.array(pts[:n]), arr[:n]


def calibrate(seg, mp4, changes):
    """조각의 pts 0이 호스트 시각으로 언제인지. 화면이 바뀌는 전이(문지기 상태 전이)마다 하나씩 구해 가운데 값."""
    pts, arr = frames(mp4)
    diff = np.zeros(len(arr))
    diff[1:] = np.abs(arr[1:] - arr[:-1]).mean(axis=(1, 2))
    est = seg["spawn"] + 0.13                # 처음 어림: 11/13 다섯 조각에서 잰 adb 띄움 → 첫 프레임 0.11~0.16초
    found = []
    for g, a, b in changes:
        if (a, b) not in VISUAL or not (seg["spawn"] + 1.0 < g < seg["stop"] - 1.0):
            continue
        lo, hi = g - est - 0.6, g - est + 0.8   # 넓게 잡으면 바로 앞의 탭 누르기 같은 다른 화면 변화를 잡는다
        idx = np.where((pts >= lo) & (pts <= hi))[0]
        if len(idx) < 3:
            continue
        w = diff[idx]
        if w.max() < 2.0:                    # 화면이 거의 안 바뀐 전이(ARMING 등)는 쓰지 않는다
            continue
        first = idx[np.argmax(w >= 0.3 * w.max())]
        found.append({"log": g, "from": a, "to": b, "pts": float(pts[first]), "diff": float(w.max()),
                      "zero": g + RENDER - float(pts[first])})
    if not found:
        raise SystemExit(f"{mp4}: 맞출 전이를 못 찾았다")
    zero = float(np.median([f["zero"] for f in found]))
    if not (0.0 <= zero - seg["spawn"] <= 0.4):
        # 화면 녹화가 adb를 띄우기 전에 시작했거나 너무 늦게 시작한 값은 잘못 잡은 것이다(전이 직후 그리기가 늦은 때 등).
        # 11/13 다섯 조각의 실측(0.11~0.16초) 가운데인 0.13초를 쓴다.
        for f in found:
            f["rejected_zero_minus_spawn_s"] = round(f["zero"] - seg["spawn"], 3)
        zero = seg["spawn"] + 0.13
    for f in found:
        f["resid_ms"] = round((f["zero"] - zero) * 1000)
    return zero, found, float(pts[-1])


def audio_checks(wav, t_audio, changes, log):
    """가수 음량이 올라가는 전이마다: 녹음 속 보컬/반주 비가 바뀐 시각 − 로그 시각(= 화면 대비 소리 지연).
    곡과 재생 위치는 같은 때의 「들킬 뻔 … song= pos=」 로그에서 얻는다(av_sync.py)."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import av_sync as A
    rec = A.load_rec(wav)
    songs = {}
    out = []
    for g, a, b in changes:
        if a != "KARAOKE" or b not in ("NORMAL", "RETURN"):
            continue
        near = [(t, m) for t, tag, msg in log if abs(t - g) < 0.6 and (m := re.search(r"song=(\S+) pos=(\d+)", msg))]
        row = {"log": g, "to": b}
        if near:
            t_pos, mm = near[0]
            sid, pos = mm.group(1), int(mm.group(2)) / 1000
            if sid not in songs:
                songs[sid] = A.load_song(sid)
            acc, voc, clk = songs[sid]
            approx = (t_pos - t_audio) - pos
            off, score = A.song_offset(rec, acc, approx, at_song=max(1.0, pos - 3.0, pos - (g - t_audio) + 2.0), win=4.0)
            t, v = A.vocal_ratio(rec, acc, voc, clk, off, (g - t_audio) - 1.5, (g - t_audio) + 1.8)
            st, vb, va = A.ratio_step(t, v, g - t_audio) if len(t) > 20 else (None, None, None)
            row.update({"song": sid, "pos_s": pos, "xcorr": round(score, 2), "app_pos_to_audio_s": round(off - approx, 3),
                        "ratio_before": None if vb is None else round(vb, 2),
                        "ratio_after": None if va is None else round(va, 2),
                        "lag_s": None if st is None else round(st - (g - t_audio), 3)})
        out.append(row)
    total_db = 20 * np.log10(np.sqrt((rec ** 2).mean()) + 1e-12)
    return out, float(total_db), float(20 * np.log10(np.abs(rec).max() + 1e-12))


def conceal_gaps(wav, out_wav, t_audio, parts, min_ms=4.0, max_ms=60.0, fade_ms=2.0):
    """녹음 중 에뮬레이터가 바쁘면(11/13~14: 후진 때 후방 카메라 앱이 같이 뜰 때) qemu 출력에 수 ms~수십 ms의 완전한 무음(0)이
    끼어 소리가 툭툭 끊긴다. 곡 위치를 재 보면 그 틈 동안 곡은 계속 흘러가 있으므로(소리가 빠진 것, 멈춘 것이 아님) 잘라 내면 안 된다.
    시각은 그대로 두고 틈을 바로 앞의 같은 길이 소리로 채워 앞뒤를 fade_ms씩 겹쳐 잇는다(통화의 손실 은닉과 같은 방식).
    각 조각(영상에 쓰는 곳) 안의, max_ms 이하 무음만 건드린다. 곡 사이의 진짜 무음은 그대로다."""
    x, sr = sf.read(wav, dtype="float32", always_2d=True)
    z = (np.abs(x).max(axis=1) <= 1.0 / 32768).astype(np.int8)
    d = np.diff(np.concatenate([[0], z, [0]]))
    runs = [(a, b) for a, b in zip(np.where(d == 1)[0], np.where(d == -1)[0])
            if min_ms / 1000 * sr <= b - a <= max_ms / 1000 * sr]
    f = int(fade_ms / 1000 * sr)
    y = x.copy()
    report = []
    for p0, p1 in parts:
        a0, a1 = int((p0 - t_audio) * sr), int((p1 - t_audio) * sr)
        mine = [(a, b) for a, b in runs if a0 <= a and b <= a1 and a - (b - a) - f >= 0]
        for a, b in mine:
            n = b - a
            src = y[a - n - f:a + f].copy()          # 틈 앞의 n+2f 샘플
            w = np.ones((n + 2 * f, 1), np.float32)
            ramp = (0.5 - 0.5 * np.cos(np.linspace(0, np.pi, f, dtype=np.float32)))[:, None]
            w[:f] = ramp
            w[-f:] = ramp[::-1]
            y[a - f:b + f] = y[a - f:b + f] * (1 - w) + src * w
        report.append({"part_start_epoch": p0, "gaps": len(mine), "filled_ms": round(sum(b - a for a, b in mine) / sr * 1000, 1),
                       "at_s": [round(a / sr - (p0 - t_audio), 2) for a, b in mine]})
    sf.write(out_wav, y, sr, subtype="PCM_16")
    return report


def ass_header():
    return f"""[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Sub,{FONT},50,&H00FFFFFF,&H00FFFFFF,&H00000000,&H64000000,-1,0,0,0,100,100,0,0,1,3,0,5,40,40,0,1
Style: Tag,{FONT},30,&H00C8B4A0,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1
Style: Act,{FONT},32,&H0000D4FF,&H00FFFFFF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,5,0,0,0,1
Style: App,{FONT},32,&H00FFE14D,&H00FFFFFF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,5,0,0,0,1
Style: Head,{FONT},26,&H00A0A0A0,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,8,0,0,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""


def ts(sec):
    sec = max(0.0, sec)
    h = int(sec // 3600)
    m = int(sec % 3600 // 60)
    s = sec % 60
    return f"{h}:{m:02d}:{s:05.2f}"


def build_ass(events, p0, p1, layout, path):
    lines = [ass_header()]
    subs = [e for e in events if e["kind"] == "sub" and p0 - 0.01 <= e["t"] <= p1]
    # 조각 시작 전에 나온 마지막 자막도 이어서 보인다
    prev = [e for e in events if e["kind"] == "sub" and e["t"] < p0 - 0.01]
    if prev and (not subs or subs[0]["t"] > p0 + 0.05):
        subs.insert(0, {**prev[-1], "t": p0})
    for i, e in enumerate(subs):
        end = subs[i + 1]["t"] if i + 1 < len(subs) else p1
        if layout == "main":
            lines.append(f"Dialogue: 1,{ts(e['t'] - p0)},{ts(end - p0)},Sub,,0,0,0,,{{\\pos(960,945)}}{e['text']}")
            if e.get("scene"):
                lines.append(f"Dialogue: 0,{ts(e['t'] - p0)},{ts(end - p0)},Tag,,0,0,0,,{{\\pos(40,838)}}{e['scene']}")
        else:
            lines.append(f"Dialogue: 1,{ts(e['t'] - p0)},{ts(end - p0)},Sub,,0,0,0,,{{\\fs36\\pos(960,1026)}}{e['text']}")
    if layout == "main":
        lines.append(f"Dialogue: 0,{ts(0)},{ts(p1 - p0)},Head,,0,0,0,,{{\\pos(1680,14)}}가상 콘솔 (8088 탭)")
        acts = [e for e in events if e["kind"] in ("console", "app") and p0 - 0.01 <= e["t"] <= p1 and e["text"]]
        for i, e in enumerate(acts):
            t = e.get("at") or e["t"]
            end = min(t + 3.0, acts[i + 1].get("at") or acts[i + 1]["t"]) if i + 1 < len(acts) else min(t + 3.0, p1)
            style, who = ("Act", "콘솔") if e["kind"] == "console" else ("App", "앱")
            lines.append(f"Dialogue: 2,{ts(t - p0)},{ts(end - p0)},{style},,0,0,0,,{{\\pos(1680,768)}}{who} ▶ {e['text']}")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def console_list(frames_tsv, cdir, p0, p1, path):
    rows = []
    for line in open(frames_tsv):
        a, b, name = line.split("\t")
        rows.append((float(a), name.strip()))
    rows.sort()
    before = [r for r in rows if r[0] <= p0]
    inside = [r for r in rows if p0 < r[0] < p1]
    seq = ([(p0, before[-1][1])] if before else []) + inside
    if not seq:
        raise SystemExit("콘솔 프레임이 없다")
    with open(path, "w") as f:
        f.write("ffconcat version 1.0\n")
        for i, (t, name) in enumerate(seq):
            end = seq[i + 1][0] if i + 1 < len(seq) else p1
            f.write(f"file '{os.path.join(os.path.abspath(cdir), name)}'\nduration {max(end - t, 0.001):.4f}\n")
        f.write(f"file '{os.path.join(os.path.abspath(cdir), seq[-1][1])}'\n")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 2:
        print(__doc__)
        sys.exit(2)
    rec, out = args
    layout = "lyrics" if "--layout=lyrics" in sys.argv or ("--layout" in sys.argv and "lyrics" in sys.argv) else "main"
    meta = json.load(open(os.path.join(rec, "events.json")))
    events, segs, t_audio = meta["events"], meta["segs"], meta["t_audio"]
    log = load_log(os.path.join(rec, "logcat.txt"))
    changes = gate_changes(log)

    sync = {"segments": [], "audio": {}}
    for seg in segs:
        mp4 = os.path.join(rec, seg["name"] + ".mp4")
        zero, found, dur = calibrate(seg, mp4, changes)
        seg["zero"] = zero
        sync["segments"].append({"name": seg["name"], "zero_minus_spawn_s": round(zero - seg["spawn"], 3),
                                 "duration_s": round(dur, 2), "events": found})
    starts = [e["t"] for e in events if e["kind"] == "part_start"]
    ends = [e["t"] for e in events if e["kind"] == "part_end"]
    parts = list(zip(starts, ends))
    wav = os.path.join(rec, "audio.wav")
    if "--keep-gaps" not in sys.argv:
        wav_fixed = os.path.join(rec, "audio_concealed.wav")
        sync["audio_gaps"] = conceal_gaps(wav, wav_fixed, t_audio, parts)
        print("무음 틈 메움:", sync["audio_gaps"], flush=True)
        wav = wav_fixed
    checks, rms_db, peak_db = audio_checks(wav, t_audio, changes, log)
    sync["audio"] = {"rms_db": round(rms_db, 1), "peak_db": round(peak_db, 1), "rises": checks}
    gain = float(min(24.0, max(0.0, -1.0 - peak_db)))
    sync["audio"]["gain_db"] = round(gain, 1)

    pdir = os.path.join(rec, "parts")
    os.makedirs(pdir, exist_ok=True)
    timeline = []
    acc = 0.0
    for p0, p1 in parts:
        timeline.append({"part_start_epoch": p0, "video_start_s": round(acc, 2), "dur_s": round(p1 - p0, 2)})
        acc += p1 - p0
    sync["parts"] = timeline
    # 이벤트를 영상 시각으로 옮겨 적는다(대본에 쓸 값)
    vt = []
    for e in events:
        for (p0, p1), tl in zip(parts, timeline):
            t = e.get("at") or e["t"]
            if p0 - 0.01 <= t <= p1 + 0.01:
                vt.append({"video_s": round(tl["video_start_s"] + t - p0, 2), "kind": e["kind"], "text": e.get("text", ""),
                           "scene": e.get("scene", "")})
    for t, a, b in changes:
        for (p0, p1), tl in zip(parts, timeline):
            if p0 <= t <= p1:
                vt.append({"video_s": round(tl["video_start_s"] + t - p0, 2), "kind": "gate", "text": f"{a} -> {b}"})
    sync["video_events"] = sorted(vt, key=lambda v: v["video_s"])
    with open(os.path.join(rec, "sync.json"), "w") as f:
        json.dump(sync, f, ensure_ascii=False, indent=1)
    print(json.dumps({k: sync[k] for k in ("segments", "audio", "parts")}, ensure_ascii=False, indent=1))
    if "--check-only" in sys.argv:
        return

    clips = []
    for i, (p0, p1) in enumerate(parts):
        d = p1 - p0
        seg = next(s for s in segs if s["spawn"] <= p0 <= s["stop"])
        ss_v = p0 - seg["zero"]
        if ss_v < 0:
            raise SystemExit(f"조각 {i}: 화면 녹화가 조각 시작보다 늦게 시작했다({ss_v:.2f}s)")
        ass = os.path.join(pdir, f"part{i}.ass")
        build_ass(events, p0, p1, layout, ass)
        clip = os.path.join(pdir, f"part{i}.mp4")
        inputs = ["-i", os.path.join(rec, seg["name"] + ".mp4")]
        if layout == "main":
            lst = os.path.join(pdir, f"console{i}.txt")
            console_list(os.path.join(rec, "console", "frames.tsv"), os.path.join(rec, "console"), p0, p1, lst)
            inputs += ["-f", "concat", "-safe", "0", "-i", lst]
            a_idx = 2
            fc = (f"color=c=0x0b0f14:s=1920x1080:r=30:d={d:.3f}[bg];"
                  f"[0:v]fps=30,trim=start={ss_v:.3f}:duration={d:.3f},setpts=PTS-STARTPTS,scale=1440:810:flags=lanczos[emu];"
                  f"[1:v]fps=30,setpts=PTS-STARTPTS,trim=duration={d:.3f},split=2[c1][c2];"
                  "[c1]crop=177:127:1405:405,scale=283:203:flags=lanczos[gear];"
                  "[c2]crop=437:398:1145:560,scale=460:419:flags=lanczos[body];"
                  "[bg][emu]overlay=0:0:eof_action=repeat[a];[a][gear]overlay=1538:52:eof_action=repeat[b];"
                  "[b][body]overlay=1450:290:eof_action=repeat[c];"
                  f"[c]ass={ass}[v];")
        else:
            a_idx = 1
            fc = (f"color=c=0x0b0f14:s=1920x1080:r=30:d={d:.3f}[bg];"
                  f"[0:v]fps=30,trim=start={ss_v:.3f}:duration={d:.3f},setpts=PTS-STARTPTS,scale=1728:972:flags=lanczos[emu];"
                  "[bg][emu]overlay=96:0:eof_action=repeat[c];"
                  f"[c]ass={ass}[v];")
        ss_a = p0 - t_audio
        fc += (f"[{a_idx}:a]atrim=start={ss_a:.4f}:duration={d:.3f},asetpts=PTS-STARTPTS,"
               f"volume={gain:.1f}dB,alimiter=limit=0.95:level=false[aout]")
        inputs += ["-i", wav]
        cmd = ["ffmpeg", "-v", "error", "-y", *inputs, "-filter_complex", fc, "-map", "[v]", "-map", "[aout]",
               "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-r", "30",
               "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-t", f"{d:.3f}", clip]
        print("조각", i, f"{d:.1f}초", flush=True)
        subprocess.run(cmd, check=True)
        clips.append(clip)
    lst = os.path.join(pdir, "clips.txt")
    with open(lst, "w") as f:
        f.writelines(f"file '{os.path.abspath(c)}'\n" for c in clips)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy",
                    "-movflags", "+faststart", out], check=True)
    print("영상:", out, run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", out]).strip(), "초")


if __name__ == "__main__":
    main()
