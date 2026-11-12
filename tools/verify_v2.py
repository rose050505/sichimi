#!/usr/bin/env python3
"""v2(무대) 흐름을 가상 콘솔의 실제 신호로 확인한다.

  python3 tools/verify_v2.py <단계> [증거 폴더]

단계
  hide     노래방 무대 → 동승석 문 열기(시치미가 0.3초 숨음, 화면 녹화에서 프레임을 뽑음) → 동승석 착석·문 닫기(동승자 화면)
           → 착석 해제(다시 혼자, "휴, 안 들켰다") → 노래 무대 복귀.            docs/evidence/v2/hide/
  wiper    무대에서 앞 와이퍼 Low → 우비, Off → 원래대로.                       docs/evidence/v2/wiper/
  plain    1차 after/의 02·03·04번 장면을 같은 순서로 다시 찍어 이미지로 비교.   docs/evidence/ui/after_v2/compare_v1/
  shots    대표 화면: 무대, 내 기록, 점수(대피 랭킹), 설정, 주행 노래방.        docs/evidence/ui/after_v2/

전제: 앱에 기록이 있고 혼자일 때 내 기록·점수 탭이 보인다. 앱 데이터를 지우지 않는다.
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import capture_ui as u  # noqa: E402

sh, c, PKG = u.sh, u.c, u.PKG


def logs(*tags):
    return sh("adb", "logcat", "-d", "-v", "time", "-s", *[t + ":I" for t in tags])


def dismiss_cards(n=10):
    u.v.dismiss_cards(n)


def start_stage():
    u.seats(False); c("gear", "P"); c("wiper", "front", "Off")
    sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{PKG}/.MainActivity"); time.sleep(4)
    dismiss_cards()
    u.tap_any("재생·가사"); time.sleep(1)
    if not u.tap_any("일시정지"):
        u.tap_any("재생")
    else:
        u.tap_any("재생")
    time.sleep(7)
    dismiss_cards()


def frames(mp4, out, prefix, times):
    for t in times:
        sh("ffmpeg", "-loglevel", "error", "-y", "-ss", f"{t:.2f}", "-i", mp4, "-frames:v", "1", os.path.join(out, f"{prefix}_{t:05.2f}s.png"))


def hide(out):
    os.makedirs(out, exist_ok=True)
    start_stage()
    sh("adb", "logcat", "-c")
    u.shot(out, "1_stage_singing")
    sh("adb", "shell", "rm", "-f", "/sdcard/hide.mp4")
    rec = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "8", "/sdcard/hide.mp4"])
    time.sleep(2.0)
    t_open = time.time()
    c("door", "front_right", "open")
    time.sleep(3.5)
    rec.wait()
    sh("adb", "pull", "/sdcard/hide.mp4", os.path.join(out, "hide.mp4"))
    u.shot(out, "2_door_open_plain")
    c("seat", "passenger", "on"); c("door", "front_right", "close"); time.sleep(2)
    u.shot(out, "3_passenger_plain")
    texts = u.v.screen_texts()
    print("동승자 화면 글:", [t for t in texts if t in ("재생·가사", "예약 목록", "내 기록", "점수", "설정")], "노래방 글자:", [t for t in texts if "노래방" in t or "시치미" in t or "휴" in t])
    c("seat", "passenger", "off"); c("door", "front_right", "open"); c("door", "front_right", "close")
    time.sleep(9.0)  # 들킬 뻔 직후라 대기 8초
    u.shot(out, "4_relieved")
    texts = u.v.screen_texts()
    print("다시 혼자:", [t for t in texts if "휴" in t or "노래방" in t])
    time.sleep(4.5)
    u.shot(out, "5_singing_again")
    l = logs("SC.Gate", "SC.Mascot")
    open(os.path.join(out, "hide_log.txt"), "w").write(l)
    print("\n".join(x for x in l.splitlines() if re.search(r"상태|시치미|들킬|휴", x)))
    # 녹화에서 문 연 순간 앞뒤 프레임(녹화는 문 열기 약 2초 전에 시작)
    frames(os.path.join(out, "hide.mp4"), out, "rec", [1.6, 1.9, 2.05, 2.15, 2.25, 2.35, 2.5, 3.0])


def wiper(out):
    os.makedirs(out, exist_ok=True)
    start_stage()
    sh("adb", "logcat", "-c")
    u.shot(out, "1_wiper_off")
    c("wiper", "front", "Low"); time.sleep(2.5)
    u.shot(out, "2_wiper_low_raincoat")
    c("wiper", "front", "Off"); time.sleep(2.5)
    u.shot(out, "3_wiper_off_again")
    l = logs("SC.Gate", "SC.Vehicle")
    open(os.path.join(out, "wiper_log.txt"), "w").write(l)
    print("\n".join(x for x in l.splitlines() if "와이퍼" in x))


def plain(out):
    """1차와 같은 순서로 동승자 장면 02·03·04를 찍어 after/와 비교한다."""
    from PIL import Image, ImageChops
    os.makedirs(out, exist_ok=True)
    u.seats(False); c("gear", "P")
    sh("adb", "shell", "am", "force-stop", "--user", "10", PKG)
    sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{PKG}/.MainActivity"); time.sleep(4)
    dismiss_cards()
    u.tap_any("확인")
    u.tap_any("재생·가사")
    c("gear", "D"); time.sleep(2)
    if not u.tap_any("재생"):
        u.tap_any("일시정지"); u.tap_any("재생")
    time.sleep(8)
    c("seat", "passenger", "on"); time.sleep(1.5)
    u.shot(out, "02_driving_passenger")
    c("gear", "P"); time.sleep(2)
    u.shot(out, "03_parked_player_passenger")
    u.tap_any("예약 목록"); u.shot(out, "04_parked_queue_passenger")
    u.tap_any("재생·가사"); c("seat", "passenger", "off")
    v1 = os.path.join(os.path.dirname(os.path.dirname(out.rstrip("/"))), "after")
    rows = []
    # 움직이는 칸(가림): 상태 표시줄 시계, 재생 탭의 진행 막대·경과 시간·가사 칸
    masks = {"03_parked_player_passenger": [(854 + 32, 90 + 950, 854 + 700, 90 + 1130), (854 + 760, 90 + 120, 2536, 1300)],
             "02_driving_passenger": [], "04_parked_queue_passenger": []}
    for name in ("02_driving_passenger", "03_parked_player_passenger", "04_parked_queue_passenger"):
        a = Image.open(os.path.join(v1, name + ".png")).convert("RGB").crop((854, 90, 2536, 1300))
        b = Image.open(os.path.join(out, name + ".png")).convert("RGB").crop((854, 90, 2536, 1300))
        d = ImageChops.difference(a, b).convert("L").point(lambda x: 255 if x > 24 else 0)
        for (x1, y1, x2, y2) in masks[name]:
            d.paste(0, (x1 - 854, y1 - 90, x2 - 854, y2 - 90))
        n = sum(1 for p in d.getdata() if p)
        d.save(os.path.join(out, name + "_diff.png"))
        rows.append((name, n / (d.width * d.height), n))
    with open(os.path.join(out, "result.md"), "w") as f:
        f.write("| 장면 | 1차(after/)와 다른 화소 비율 | 다른 화소 수 | 가림 |\n|---|---|---|---|\n")
        for name, diff, n in rows:
            f.write(f"| {name} | {diff * 100:.3f}% | {n} | {'진행 막대·경과 시간·가사 칸' if masks[name] else '없음'} |\n")
    print(open(os.path.join(out, "result.md")).read())


def shots(out):
    os.makedirs(out, exist_ok=True)
    start_stage()
    u.shot(out, "01_stage_karaoke")
    u.tap_any("내 기록"); time.sleep(1); u.shot(out, "02_records_top")
    u.scroll(True, 2); u.shot(out, "03_records_more")
    u.tap_any("점수"); u.scroll(False, 3); time.sleep(1); u.shot(out, "04_scores")
    u.tap_any("가장 빠른 대피(이번 주)"); time.sleep(3); u.shot(out, "05_escape_rank")
    u.tap_any("점수", partial=False)
    u.tap_any("설정"); u.scroll(True, 2); u.shot(out, "06_settings_ghost")
    u.tap_any("재생·가사")
    c("gear", "D"); time.sleep(2); u.shot(out, "07_driving_karaoke")
    c("gear", "P")


def ghost_pick(out):
    """점수 탭 랭킹에서 다른 사람 줄의 「고스트로 겨루기」를 누른다."""
    os.makedirs(out, exist_ok=True)
    u.seats(False); c("gear", "P")
    sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{PKG}/.MainActivity"); time.sleep(4)
    dismiss_cards()
    u.tap_any("점수"); time.sleep(1); u.tap_any("2021 아리랑 응원가"); time.sleep(3)
    want = sys.argv[3] if len(sys.argv) > 3 else None  # 고를 줄의 점수(예: 77점)
    for _ in range(4):
        xml = u.dump()
        nodes = re.findall(r'<node [^>]*>', xml)
        btns = [n for n in nodes if 'text="고스트로 겨루기"' in n or 'content-desc="고스트로 겨루기"' in n]
        def cy(n):
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()
            return (int(b[0]) + int(b[2])) // 2, (int(b[1]) + int(b[3])) // 2
        if want:
            rows = [(cy(n)[1], cy(n)[0]) for n in nodes if f'text="{want}"' in n]
            # 4위부터는 같은 줄, 1~3위 단상은 점수 바로 아래에 버튼이 있다
            btns = [b for b in btns if rows and min(abs(cy(b)[1] - r) if abs(cy(b)[0] - x) < 260 or abs(cy(b)[1] - r) < 40 else 9999 for r, x in rows) < 160]
        if btns:
            x, y = cy(btns[0]); sh("adb", "shell", "input", "tap", str(x), str(y))
            break
        u.scroll(True, 1)
    time.sleep(5)
    u.scroll(False, 4)
    u.shot(out, "1_ghost_banner")
    l = logs("SC.Ghost", "SC.Rank")
    open(os.path.join(out, "pick_log.txt"), "w").write(l)
    print("\n".join(x for x in l.splitlines() if "고스트" in x or "받음" in x))


def ghost_listen(out, shift="600", voice=None):
    """고른 곡을 처음부터 노래방으로 틀고 스피커 소리를 담아 고스트 위치를 잰다. 중간에 동승석 착석 -> 고스트 0."""
    os.makedirs(out, exist_ok=True)
    u.seats(False); c("gear", "P")
    sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{PKG}/.MainActivity"); time.sleep(3)
    dismiss_cards()
    u.tap_any("예약 목록"); time.sleep(1)
    sh("adb", "logcat", "-c")
    u.tap_any("2021 아리랑 응원가"); t0 = time.time()
    u.tap_any("재생·가사")
    time.sleep(14)
    wav = os.path.join(out, "ghost_listen.wav")
    rec = subprocess.Popen(["parecord", "--device=@DEFAULT_MONITOR@", "--rate=48000", "--channels=2", "--file-format=wav", wav])
    time.sleep(2); u.shot(out, "2_stage_with_ghost")
    time.sleep(34)
    rec.terminate(); rec.wait()
    c("seat", "passenger", "on"); time.sleep(3)
    u.shot(out, "3_passenger_ghost_gone")
    c("seat", "passenger", "off"); time.sleep(1)
    u.tap_any("일시정지")
    l = logs("SC.Ghost", "SC.Gate")
    open(os.path.join(out, "listen_log.txt"), "w").write(l)
    print("\n".join(x for x in l.splitlines() if re.search(r"고스트|상태", x))[-2500:])
    args = ["python3", "tools/align_ghost.py", wav, "01-arirang", shift] + (["--voice-from", voice] if voice else [])
    print(sh(*args, check=False))


if __name__ == "__main__":
    step = sys.argv[1]
    default = {"hide": "docs/evidence/v2/hide", "wiper": "docs/evidence/v2/wiper",
               "ghost_pick": "docs/evidence/v2/ghost", "ghost_listen": "docs/evidence/v2/ghost",
               "plain": "docs/evidence/ui/after_v2/compare_v1", "shots": "docs/evidence/ui/after_v2"}[step]
    out = sys.argv[2] if len(sys.argv) > 2 else default
    {"hide": hide, "wiper": wiper, "plain": plain, "shots": shots, "ghost_pick": ghost_pick, "ghost_listen": ghost_listen}[step](out) if step != "ghost_listen" else ghost_listen(out, *(sys.argv[3:5]))
