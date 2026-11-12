#!/usr/bin/env python3
"""README 첫 화면 GIF를 가상 콘솔의 실제 신호로 찍는다(주차, 운전석만 착석).

  python3 tools/make_readme_gif.py [docs/media/scene1-2.gif]

흐름: 동승석 문이 열린 채 아리랑 재생(평범한 재생 화면) → 녹화 시작 → 문 닫힘 → 5초 뒤 「혼자만의 노래방」 무대(가사 채움, 시치미)
→ 동승석 문 열림 → 곧바로 평범한 재생 화면(시치미는 0.3초 동안 아래로 숨음). 앱 데이터를 지우지 않는다.
"""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import verify_v2 as w  # noqa: E402

u, c, sh, PKG = w.u, w.c, w.sh, w.PKG
out = sys.argv[1] if len(sys.argv) > 1 else "docs/media/scene1-2.gif"

u.seats(False); c("gear", "P"); c("wiper", "front", "Off")
sh("adb", "shell", "am", "force-stop", "--user", "10", PKG)
sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{PKG}/.MainActivity"); time.sleep(4)
w.dismiss_cards()
u.tap_any("확인")  # 오늘 요약 띠
u.tap_any("재생·가사"); time.sleep(1)
c("door", "front_right", "open"); time.sleep(1.5)
# 곡을 처음부터: 다른 곡으로 갔다가 아리랑을 바로 재생(예약 목록에서 곡을 누르면 처음부터)
u.tap_any("예약 목록"); time.sleep(1); u.tap_any("2021 아리랑 응원가"); time.sleep(1); u.tap_any("재생·가사")
t_play = time.time()
time.sleep(max(0.0, 6.5 - (time.time() - t_play)))
sh("adb", "shell", "rm", "-f", "/sdcard/readme.mp4")
rec = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "17", "--size", "1280x720", "/sdcard/readme.mp4"])
time.sleep(2.0)
c("door", "front_right", "close")       # 녹화 2초: 혼자·밀폐 → 5초 대기
time.sleep(10.5)                        # 녹화 7초쯤 무대, 9.6초쯤 첫 가사
c("door", "front_right", "open")        # 녹화 12.5초: 들킬 뻔
time.sleep(3.0)
c("door", "front_right", "close")
rec.wait()
tmp = "/tmp/readme.mp4" if not os.environ.get("TMPDIR") else os.path.join(os.environ["TMPDIR"], "readme.mp4")
sh("adb", "pull", "/sdcard/readme.mp4", tmp)
pal = tmp + ".png"
sh("ffmpeg", "-loglevel", "error", "-y", "-i", tmp, "-vf", "fps=6,scale=960:-1:flags=lanczos,palettegen=max_colors=128", pal)
sh("ffmpeg", "-loglevel", "error", "-y", "-i", tmp, "-i", pal, "-lavfi", "fps=6,scale=960:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=4", out)
u.tap_any("일시정지")
print("GIF", out, os.path.getsize(out), "바이트")
