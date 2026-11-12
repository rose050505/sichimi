#!/usr/bin/env python3
"""녹음 올리기·듣기를 에뮬레이터에서 확인한다.

  python3 tools/verify_take.py <증거 폴더> <단계>

단계
  setup    설정: 별명 test-delete, 「전국 랭킹에 올리기」를 켤 때 동의 화면을 찍고 켠다, 노래 점수 켬
  sing     아리랑을 끝까지 채점(가상 마이크에 원곡 보컬, verify_g5 full) -> 녹음이 차 안에 남는지 로그로 본다
  preview  주차해서 「점수」 탭의 내 녹음을 미리 듣는다. 스피커 소리를 25초 담아 목소리와 반주가 맞는지 잰다(align_take.py)
  drive    미리 듣는 중에 기어 D -> 멈추는지, 다시 P로 와도 스스로 다시 틀지 않는지
  solo     미리 듣는 중에 동승석 착석 -> 멈추고 점수 탭이 숨는지
  upload   「녹음 올리기」를 누르고 결과 글과 로그를 남긴다
  listen   랭킹 줄의 「듣기」(서버에서 받아 틀기) -> 스피커 소리를 담아 맞는지 잰다
  delete   내 녹음 「지우기」 -> 서버와 차 안에서 지워지는지

전제: verify_g5.py와 같다(가상 마이크 sichimi_mic, 콘솔 하트비트). 이것은 자동 시험이다. 시연에서는 사람이 직접 부른다.
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import verify_scenes as v  # noqa: E402
import verify_g5 as g5  # noqa: E402

sh, c = v.sh, v.c
PKG = v.PKG


def dump():
    sh("adb", "shell", "uiautomator", "dump", "--user", "10", "/sdcard/ui.xml", check=False)
    return sh("adb", "shell", "cat", "/sdcard/ui.xml")


def find(text, xml=None, partial=False):
    xml = xml or dump()
    pat = (r'text="[^"]*%s[^"]*"' if partial else r'text="%s"') % re.escape(text)
    m = re.search(pat + r'[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(text, partial=False, scroll=True):
    for _ in range(5):
        p = find(text, partial=partial)
        if p:
            sh("adb", "shell", "input", "tap", str(p[0]), str(p[1]))
            return True
        if not scroll:
            return False
        sh("adb", "shell", "input", "swipe", "1700", "1100", "1700", "500", "400"); time.sleep(1)
    return False


def scroll_top():
    for _ in range(4):
        sh("adb", "shell", "input", "swipe", "1700", "500", "1700", "1300", "300")
    time.sleep(0.8)


def shot(out, name):
    with open(os.path.join(out, name + ".png"), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)


def logs(*tags):
    return sh("adb", "logcat", "-d", "-v", "time", "-s", *[t + ":I" for t in tags])


def open_tab(name):
    g5.cabin_alone("P")
    sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{PKG}/.MainActivity"); time.sleep(3)
    v.tap_text(name); time.sleep(2)


def record(path, sec):
    """호스트 스피커로 나가는 소리(에뮬레이터 출력)를 담는다."""
    return subprocess.Popen(["parecord", "--device=@DEFAULT_MONITOR@", "--rate=48000", "--channels=2", "--file-format=wav", path])


def setup(out):
    open_tab("설정")
    # 별명
    if not tap("바꾸기"):
        raise SystemExit("별명 바꾸기를 찾지 못함")
    time.sleep(1)
    sh("adb", "shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(14):
        sh("adb", "shell", "input", "keyevent", "KEYCODE_DEL")
    sh("adb", "shell", "input", "text", "test-delete")
    time.sleep(0.5)
    tap("저장"); time.sleep(1)
    sh("adb", "shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(0.5)
    # 랭킹 올리기: 스위치를 누르면 동의 화면이 먼저 뜬다
    scroll_top()
    tap("전국 랭킹에 올리기"); time.sleep(1.5)
    shot(out, "a1_consent")
    texts = v.screen_texts()
    print("동의 화면:", [t for t in texts if "공개" in t][:3])
    tap("알겠습니다, 켜기"); time.sleep(1.5)
    shot(out, "a2_rank_on")
    g5.set_switch("노래 점수(마이크)", True)
    print(sh("adb", "shell", "run-as", PKG, "cat", "shared_prefs/settings.xml", check=False))


def sing(out):
    sys.argv = ["verify_g5.py", out, "full", "--sec", "206"]
    g5.main()
    time.sleep(30)  # 압축·저장 기다림(에뮬레이터에서 196초 녹음에 약 17초)
    l = logs("SC.Take", "SC.Rank", "SC.Score")
    open(os.path.join(out, "b_sing_take_log.txt"), "w").write(l)
    for line in l.splitlines():
        if re.search(r"녹음|압축|지연|랭킹|점수 곡", line):
            print(line)


def play_and_capture(out, name, button, sec=40):
    sh("adb", "logcat", "-c")
    wav = os.path.join(out, name + ".wav")
    rec = record(wav, sec)
    t0 = time.time()
    if not tap(button, partial=True):
        rec.terminate(); raise SystemExit(f"'{button}' 버튼을 찾지 못함")
    time.sleep(3)
    shot(out, name + "_playing")
    time.sleep(max(0, sec - (time.time() - t0)))
    rec.terminate(); rec.wait()
    l = logs("SC.TakePlay", "SC.Take", "SC.Rank", "SC.Player")
    open(os.path.join(out, name + "_log.txt"), "w").write(l)
    print(l)
    return wav


def preview(out):
    open_tab("점수")
    shot(out, "c1_scores_my_take")
    wav = play_and_capture(out, "c2_preview", "미리 듣기")
    tap("정지"); time.sleep(1)
    print(sh("python3", "tools/align_take.py", wav, "01-arirang", check=False))


def drive(out):
    open_tab("점수")
    sh("adb", "logcat", "-c")
    tap("미리 듣기", partial=True); time.sleep(6)
    c("gear", "D"); time.sleep(2)
    shot(out, "d1_driving")
    c("gear", "P"); time.sleep(4)
    shot(out, "d2_parked_again")
    l = logs("SC.TakePlay", "SC.Gate")
    open(os.path.join(out, "d_drive_log.txt"), "w").write(l)
    print(l)
    print("P로 돌아온 뒤 화면:", [t for t in v.screen_texts() if t in ("미리 듣기", "정지", "불러오는 중…")])


def solo(out):
    open_tab("점수")
    sh("adb", "logcat", "-c")
    tap("미리 듣기", partial=True); time.sleep(6)
    c("seat", "passenger", "on"); time.sleep(2)
    shot(out, "e1_passenger")
    print("동승자 탄 뒤 탭:", [t for t in v.screen_texts() if t in ("점수", "설정", "내 기록", "재생·가사", "예약 목록")])
    c("seat", "passenger", "off"); time.sleep(1)
    l = logs("SC.TakePlay", "SC.Gate")
    open(os.path.join(out, "e_solo_log.txt"), "w").write(l)
    print(l)


def upload(out):
    open_tab("점수")
    sh("adb", "logcat", "-c")
    tap("녹음 올리기"); time.sleep(10)
    shot(out, "f1_after_upload")
    print("화면 글:", [t for t in v.screen_texts() if "올" in t or "녹음" in t])
    l = logs("SC.Take", "SC.Rank")
    open(os.path.join(out, "f_upload_log.txt"), "w").write(l)
    print(l)


def listen(out):
    open_tab("점수")
    time.sleep(3)
    shot(out, "g1_rank_listen_button")
    wav = play_and_capture(out, "g2_listen_server", "듣기 ")
    tap("정지"); time.sleep(1)
    print(sh("python3", "tools/align_take.py", wav, "01-arirang", check=False))


def delete(out):
    open_tab("점수")
    sh("adb", "logcat", "-c")
    tap("지우기"); time.sleep(1)
    shot(out, "h1_delete_confirm")
    tap("서버와 차에서 지우기") or tap("차에서 지우기"); time.sleep(6)
    shot(out, "h2_after_delete")
    l = logs("SC.Take", "SC.Rank")
    open(os.path.join(out, "h_delete_log.txt"), "w").write(l)
    print(l)


if __name__ == "__main__":
    out, step = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    {"setup": setup, "sing": sing, "preview": preview, "drive": drive, "solo": solo,
     "upload": upload, "listen": listen, "delete": delete}[step](out)
