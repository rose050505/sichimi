#!/usr/bin/env python3
"""화면 다듬기 전후 비교용으로 같은 장면을 같은 순서로 찍는다.

  python3 tools/capture_ui.py docs/evidence/ui/before
  python3 tools/capture_ui.py docs/evidence/ui/after
  python3 tools/capture_ui.py --pair docs/evidence/ui     # before/after 같은 이름끼리 나란히 붙인 compare/*.png

전제: 앱 데이터에 점수·내 녹음·기록이 있다(verify_take.py setup, sing). 앱 데이터를 지우지 않는다.
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import verify_scenes as v  # noqa: E402

sh, c, PKG = v.sh, v.c, v.PKG


def dump():
    sh("adb", "shell", "uiautomator", "dump", "--user", "10", "/sdcard/ui.xml", check=False)
    return sh("adb", "shell", "cat", "/sdcard/ui.xml")


def tap_any(label, partial=False):
    """text나 content-desc가 [label]인 것을 누른다."""
    xml = dump()
    q = re.escape(label)
    pat = r'(?:text|content-desc)="%s"' % (r'[^"]*%s[^"]*' % q if partial else q)
    for m in re.finditer(r'<node [^>]*>', xml):
        node = m.group(0)
        if re.search(pat, node):
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            if b:
                x1, y1, x2, y2 = map(int, b.groups())
                sh("adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                return True
    return False


def shot(out, name):
    time.sleep(1.2)
    with open(os.path.join(out, name + ".png"), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    print("찍음", name)


def seats(passenger):
    c("seat", "driver", "on"); c("seat", "passenger", "on" if passenger else "off")
    c("seat", "rear_left", "off"); c("seat", "rear_right", "off")
    for z in ("front_left", "front_right", "rear_left", "rear_right"):
        c("door", z, "close"); c("window", z, "0")
    c("turn", "off")


def scroll(down=True, times=1):
    for _ in range(times):
        if down:
            sh("adb", "shell", "input", "swipe", "1700", "1100", "1700", "450", "400")
        else:
            sh("adb", "shell", "input", "swipe", "1700", "450", "1700", "1150", "300")
    time.sleep(1)


def capture(out):
    os.makedirs(out, exist_ok=True)
    seats(False); c("gear", "P")
    sh("adb", "shell", "am", "force-stop", "--user", "10", PKG)
    sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{PKG}/.MainActivity"); time.sleep(4)
    tap_any("재생·가사")
    c("gear", "D"); time.sleep(2)
    if not tap_any("재생"):
        sh("adb", "shell", "input", "tap", "1693", "812")
    time.sleep(8)  # 대기 5초 뒤 노래방
    shot(out, "01_driving_karaoke")
    c("seat", "passenger", "on"); time.sleep(1.5)
    shot(out, "02_driving_passenger")
    c("gear", "P"); time.sleep(2)
    shot(out, "03_parked_player_passenger")
    tap_any("예약 목록"); shot(out, "04_parked_queue_passenger")
    tap_any("재생·가사")
    c("seat", "passenger", "off"); time.sleep(10)  # 다시 노래방(들킬 뻔 직후라 8초)
    shot(out, "05_parked_player_karaoke")
    tap_any("확인")  # 오늘 요약 닫기
    time.sleep(1)
    shot(out, "06_parked_player_karaoke_lyrics")
    tap_any("예약 목록"); shot(out, "07_parked_queue")
    tap_any("내 기록"); shot(out, "08_parked_records")
    tap_any("점수"); scroll(False, 3); shot(out, "09_parked_scores_top")
    scroll(True, 2); shot(out, "10_parked_scores_takes_rank")
    tap_any("설정"); scroll(False, 3); shot(out, "11_parked_settings_top")
    scroll(True, 3); shot(out, "12_parked_settings_bottom")
    # 동의 안내: 랭킹 올리기를 껐다 다시 켜면 뜬다
    scroll(False, 3); scroll(True, 1)
    tap_any("전국 랭킹에 올리기"); time.sleep(1.5)
    tap_any("전국 랭킹에 올리기"); time.sleep(1.5)
    shot(out, "13_consent")
    tap_any("알겠습니다, 켜기") or tap_any("켜기", partial=True)
    time.sleep(1)
    tap_any("재생·가사"); time.sleep(1)
    tap_any("일시정지")


def pair(root):
    from PIL import Image, ImageDraw
    b, a, o = os.path.join(root, "before"), os.path.join(root, "after"), os.path.join(root, "compare")
    os.makedirs(o, exist_ok=True)
    for name in sorted(os.listdir(b)):
        if not name.endswith(".png") or not os.path.exists(os.path.join(a, name)):
            continue
        ib, ia = Image.open(os.path.join(b, name)).convert("RGB"), Image.open(os.path.join(a, name)).convert("RGB")
        # 앱이 그리는 오른쪽 부분만(왼쪽은 시스템 계기판)
        box = (854, 90, 2536, 1300)
        ib, ia = ib.crop(box), ia.crop(box)
        w, h = ib.size
        img = Image.new("RGB", (w * 2 + 40, h + 80), (255, 255, 255))
        img.paste(ib, (0, 80)); img.paste(ia, (w + 40, 80))
        d = ImageDraw.Draw(img)
        try:
            from PIL import ImageFont
            f = ImageFont.truetype("/usr/share/fonts/truetype/nanum/NanumBarunGothic.ttf", 44)
        except Exception:
            f = None
        d.text((20, 15), "전 " + name[:-4], fill=(0, 0, 0), font=f)
        d.text((w + 60, 15), "후", fill=(0, 0, 0), font=f)
        img = img.resize((img.width // 2, img.height // 2))
        img.save(os.path.join(o, name))
        print("짝", name)


if __name__ == "__main__":
    if sys.argv[1] == "--pair":
        pair(sys.argv[2])
    else:
        capture(sys.argv[1])
