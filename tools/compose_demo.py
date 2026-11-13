#!/usr/bin/env python3
"""record_demo.py가 담은 것(에뮬레이터 화면 조각, qemu 소리, 콘솔 탭 프레임, 이벤트)을 한 영상으로 합친다(11/13).

  ~/.venvs/demucs/bin/python -I tools/compose_demo.py recordings/demo_main docs/media/demo_main.mp4 [--layout main|lyrics] [--check-only] [--keep-gaps]
      [--mic] [--mic-lag=초] [--mic-db=dB]

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
- --mic(11/14, record_demo.py score): 사람 목소리(mic.wav, 호스트 기본 입력)를 반주(qemu 출력)에 섞는다. align_mic 참고.
  사람은 이어폰에서 들리는 반주에 맞춰 부른다. qemu 출력은 앱 화면보다 약 0.45초 늦게 들리므로(G2) 목소리는 화면이 아니라 녹음 속 반주에 맞춘다.
  호스트 시계로 두 녹음을 겹친 뒤 남는 틈(이어폰·마이크 장치 지연, 사람이 부르는 박자)은 목소리와 원곡 보컬의 크기 굴곡을 맞대어 잰다.
  --mic-lag=초로 직접 정할 수 있다. 크기는 노래 구간의 목소리 RMS를 반주 RMS + --mic-db(기본 +1dB)에 맞추고, 곡 처음·끝과 조각 앞뒤에서 0.5초씩 페이드한다.
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


def calibrate(seg, mp4, changes, allow_fallback=False):
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
        if not allow_fallback:
            raise SystemExit(f"{mp4}: 맞출 전이를 못 찾았다")
        # 노래 점수 녹화는 내내 노래방이라 화면이 크게 바뀌는 전이가 없다 → 11/13 실측 가운데 값(adb 띄움 + 0.13초, ±0.03초)
        return seg["spawn"] + 0.13, [{"fallback": "no visual transition, spawn + 0.13 s"}], float(pts[-1])
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
        # 곡을 멈춘 뒤 같은 거의 무음(−80dBFS 아래) 속의 0은 끊김이 아니다(11/14 노래 점수 시험에서 255개가 잡힘)
        mine = [(a, b) for a, b in runs if a0 <= a and b <= a1 and a - (b - a) - f >= 0
                and np.sqrt((x[max(0, a - int(0.05 * sr)):a] ** 2).mean()) > 1e-4]
        for a, b in mine:
            n = b - a
            src = y[a - n - f:a + f].copy()          # 틈 앞의 n+2f 샘플
            w = np.ones((n + 2 * f, 1), np.float32)
            ramp = (0.5 - 0.5 * np.cos(np.linspace(0, np.pi, f, dtype=np.float32)))[:, None]
            w[:f] = ramp
            w[-f:] = ramp[::-1]
            y[a - f:b + f] = y[a - f:b + f] * (1 - w) + src * w
        report.append({"part_start_epoch": p0, "gaps": len(mine), "filled_ms": round(float(sum(b - a for a, b in mine) / sr * 1000), 1),
                       "at_s": [round(float(a / sr - (p0 - t_audio)), 2) for a, b in mine]})
    sf.write(out_wav, y, sr, subtype="PCM_16")
    return report


def _envelope(x, sr, hop):
    """목소리 대역(150~3000Hz) 크기 굴곡(dB, hop마다). 바닥은 최댓값 − 45dB로 자른다."""
    from scipy.signal import butter, sosfilt
    y = sosfilt(butter(4, [150, 3000], "bandpass", fs=sr, output="sos"), x)
    n = len(y) // hop
    e = 10 * np.log10((y[:n * hop].reshape(n, hop) ** 2).mean(axis=1) + 1e-12)
    return np.maximum(e, e.max() - 45.0)


def align_mic(rec, meta, t_audio, music_wav, gain_db, out_wav, lag=None, rel_db=1.0, fade=0.5):
    """사람 목소리(mic.wav)를 녹음(qemu 출력) 시각축에 옮기고 크기를 맞춰 out_wav로 쓴다(길이·시각 = 녹음과 같음).

    1. 곡 0초가 녹음의 어디인지: 녹음과 원곡 반주의 교차 상관(av_sync.song_offset).
    2. 목소리 지연 L: 목소리 크기 굴곡과 원곡 보컬 크기 굴곡(녹음 시각에 둔 것)의 상관이 가장 큰 L(−0.2~+0.8초, 10ms 단위).
       반주가 녹음에 담긴 때와 사람 귀에 들린 때의 차이(이어폰 지연), 마이크 지연, 박자 습관이 모두 L에 들어간다.
       상관이 0.2보다 낮으면 믿지 않고 0(호스트 시계 그대로)을 쓴다. lag를 주면 그 값을 쓴다.
       참고로 파형 교차 상관도 적는다(원곡 보컬을 흘린 시험이면 정확한 답, 사람 목소리면 상관이 낮게 나온다).
    3. 크기: 곡 구간에서 목소리가 나는 50ms 창의 RMS를 반주(키운 뒤) RMS + rel_db에 맞춘다.
    4. 곡 구간 밖은 0, 곡 처음·끝에서 fade초 페이드."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import av_sync as A
    from scipy.signal import butter, sosfilt
    sr = A.SR
    mus = A.load_rec(music_wav)
    m, msr = sf.read(os.path.join(rec, "mic.wav"), dtype="float32", always_2d=True)
    assert msr == sr, msr
    m = sosfilt(butter(2, 90, "highpass", fs=sr, output="sos"), m.mean(axis=1)).astype(np.float32)
    d0 = meta["mic"]["t"] - t_audio                  # mic.wav 0초의 녹음 시각
    song = meta["song"]
    acc, voc, clk = A.load_song(song)
    ev = next(e for e in meta["events"] if e["kind"] == "song_start")
    approx = ev["t_heard_guess"] - t_audio
    # 곡 처음 몇 초는 반주가 성겨 상관이 낮다(11/14 시험: 2초에서 0.27, 24ms 어긋남). 여러 곳에서 재어 상관이 가장 큰 값
    ends = [e["t"] for e in meta["events"] if e["kind"] == "app" and e["text"].startswith("시험 끝")]
    heard = (min(ends) - t_audio - approx) if ends else len(acc) / sr
    tries = [A.song_offset(mus, acc, approx, search=1.5, at_song=at, win=6.0)
             for at in (6.0, 12.0, 20.0, 40.0, 80.0) if at + 6.0 < heard and approx + at + 7.5 < len(mus) / sr]
    off, xc = max(tries or [A.song_offset(mus, acc, approx, search=1.5, at_song=2.0, win=6.0)], key=lambda q: q[1])
    w0 = off
    w1 = off + len(acc) / sr
    if ends:                                        # 시험: 도중에 멈춤(+ 출력 지연)
        w1 = min(w1, ends[0] - t_audio + 0.45)
    w1 = min(w1, len(mus) / sr)
    info = {"song": song, "song0_in_audio_s": round(off, 3), "song0_minus_guess_s": round(off - approx, 3),
            "song_xcorr": round(xc, 2), "window_audio_s": [round(w0, 2), round(w1, 2)], "mic_start_in_audio_s": round(d0, 3)}

    hop = int(0.01 * sr)
    # 원곡 보컬 굴곡(녹음 시각축) — 보컬이 있는 곳만
    s0, s1 = max(0, int(-off * sr)), int((w1 - off) * sr)
    s0 = max(s0, int((w0 - off) * sr))
    e_ref = _envelope(voc[s0:s1], sr, hop)
    t_ref = off + (s0 + hop / 2 + hop * np.arange(len(e_ref))) / sr
    e_mic = _envelope(m, sr, hop)
    t_mic = d0 + (hop / 2 + hop * np.arange(len(e_mic))) / sr
    lags = np.round(np.arange(-0.2, 0.801, 0.01), 3)
    cors = []
    for L in lags:
        ok = (t_ref + L >= t_mic[0]) & (t_ref + L <= t_mic[-1])
        if ok.sum() < 300:
            cors.append(np.nan)
            continue
        a = e_ref[ok]
        b = np.interp(t_ref[ok] + L, t_mic, e_mic)
        cors.append(float(np.corrcoef(a, b)[0, 1]))
    cors = np.array(cors)
    k = int(np.nanargmax(cors)) if np.isfinite(cors).any() else None
    est = float(lags[k]) if k is not None else None
    info["env_lag_s"] = est
    info["env_corr"] = None if k is None else round(float(cors[k]), 3)
    info["env_corr_at_0"] = round(float(cors[np.argmin(np.abs(lags))]), 3) if np.isfinite(cors).any() else None
    # 참고: 파형 교차 상관(목소리가 원곡 보컬 그대로일 때만 뜻이 있다). 보컬이 많은 12초
    try:
        c_at = max(w0 + 5.0, off + (meta_vocal_at(song) or 0) + 1.0)
        a = voc[int((c_at - off) * sr):int((c_at - off + 12) * sr)]
        i0 = int((c_at - d0 - 0.3) * sr)
        b = m[max(0, i0):i0 + len(a) + int(1.4 * sr)]
        n = 1 << int(np.ceil(np.log2(len(a) + len(b))))
        cc = np.fft.irfft(np.fft.rfft(b, n) * np.conj(np.fft.rfft(a, n)), n)[:len(b) - len(a)]
        j = int(np.argmax(np.abs(cc)))
        info["wave_lag_s"] = round((max(0, i0) + j) / sr + d0 - c_at, 3)
        info["wave_corr"] = round(float(np.abs(cc[j]) / (np.linalg.norm(a) * np.linalg.norm(b[j:j + len(a)]) + 1e-12)), 3)
    except Exception as e:  # noqa: BLE001 — 참고 값일 뿐
        info["wave_lag_s"] = f"error {e}"
    if lag is not None:
        use, why = lag, "--mic-lag"
    elif est is not None and info["env_corr"] >= 0.2:
        use, why = est, "크기 굴곡 상관"
    else:
        use, why = 0.0, "상관이 낮아 호스트 시계 그대로"
    info["lag_used_s"], info["lag_from"] = use, why

    # 녹음 시각 t의 목소리 = mic.wav의 (t + L − d0)초
    n = len(mus)
    y = np.zeros(n, np.float32)
    shift = int(round((use - d0) * sr))             # y[k] = m[k + shift]
    k0, k1 = max(0, -shift), min(n, len(m) - shift)
    if k1 > k0:
        y[k0:k1] = m[k0 + shift:k1 + shift]
    env = np.zeros(n, np.float32)
    a0, a1 = int(max(0, w0) * sr), int(w1 * sr)
    env[a0:a1] = 1.0
    f = int(fade * sr)
    ramp = (0.5 - 0.5 * np.cos(np.linspace(0, np.pi, f, dtype=np.float32)))
    if a1 - a0 > 2 * f:
        env[a0:a0 + f] = ramp
        env[a1 - f:a1] = ramp[::-1]
    y *= env
    # 크기 맞추기
    g = 10 ** (gain_db / 20)
    win = int(0.05 * sr)
    def frames_rms(x):
        q = len(x) // win
        return np.sqrt((x[:q * win].reshape(q, win) ** 2).mean(axis=1) + 1e-12)
    r_mus = frames_rms(mus[a0:a1] * g)
    music_db = 20 * np.log10(np.sqrt((r_mus[r_mus > 1e-4] ** 2).mean()) + 1e-12) if (r_mus > 1e-4).any() else -30.0
    r_v = frames_rms(y[a0:a1])
    floor = np.percentile(r_v, 10)
    act = r_v > max(floor * 10 ** (12 / 20), 1e-4)
    voice_db = 20 * np.log10(np.sqrt((r_v[act] ** 2).mean()) + 1e-12) if act.any() else None
    mic_gain = float(np.clip(music_db + rel_db - voice_db, -20, 40)) if voice_db is not None else 0.0
    y *= 10 ** (mic_gain / 20)
    info.update({"music_rms_db": round(float(music_db), 1), "voice_rms_db_raw": None if voice_db is None else round(float(voice_db), 1),
                 "voice_active_frac": round(float(act.mean()), 2), "mic_gain_db": round(mic_gain, 1), "rel_db": rel_db, "fade_s": fade})
    sf.write(out_wav, y, sr, subtype="FLOAT")
    return info


def meta_vocal_at(song):
    try:
        p = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "songs", song, "meta.json")
        return json.load(open(p, encoding="utf-8"))["countIn"]["vocalAtMs"] / 1000
    except Exception:  # noqa: BLE001
        return None


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
        zero, found, dur = calibrate(seg, mp4, changes, allow_fallback=meta.get("mode") == "score")
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
    mic_wav = None
    if "--mic" in sys.argv:
        opt = {a.split("=")[0]: a.split("=", 1)[1] for a in sys.argv if a.startswith("--mic-") and "=" in a}
        mic_wav = os.path.join(rec, "mic_aligned.wav")
        sync["mic"] = align_mic(rec, meta, t_audio, wav, gain, mic_wav,
                                lag=float(opt["--mic-lag"]) if "--mic-lag" in opt else None,
                                rel_db=float(opt.get("--mic-db", 1.0)))
        print("목소리:", json.dumps(sync["mic"], ensure_ascii=False), flush=True)

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
        if mic_wav:
            fo = max(0.0, d - 0.5)
            fc += (f"[{a_idx}:a]atrim=start={ss_a:.4f}:duration={d:.3f},asetpts=PTS-STARTPTS,volume={gain:.1f}dB,"
                   "aformat=sample_fmts=fltp:channel_layouts=stereo[mus];"
                   f"[{a_idx + 1}:a]atrim=start={ss_a:.4f}:duration={d:.3f},asetpts=PTS-STARTPTS,"
                   f"afade=t=in:st=0:d=0.5,afade=t=out:st={fo:.3f}:d=0.5,"
                   "aformat=sample_fmts=fltp:channel_layouts=stereo[mic];"
                   "[mus][mic]amix=inputs=2:normalize=0:duration=first,alimiter=limit=0.95:level=false[aout]")
            inputs += ["-i", wav, "-i", mic_wav]
        else:
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
