#!/usr/bin/env python3
"""시연 영상 녹화(11/13). 에뮬레이터 화면·소리·콘솔 탭·이벤트 시각을 따로 담고, 합치기는 compose_demo.py가 한다.

- 에뮬레이터 화면: adb shell screenrecord(1920x1080, 한 조각 180초까지). 조각 시작 시각은 합칠 때 문지기 로그와 화면 변화로 맞춘다.
- 소리: parecord --monitor-stream으로 qemu 출력 스트림만 받는다(호스트 스피커 음량·음소거와 상관없고 다른 소리가 섞이지 않는다).
- 콘솔 탭: tools/console_tab.js가 8088 콘솔 페이지를 헤드리스로 열어 프레임을 저장하고, 문·창문·기어는 그 페이지의 버튼을 실제로 누른다.
  좌석(착석)은 콘솔 페이지 첫 화면에 버튼이 없어 콘솔 페이지와 같은 HTTP 경로(tools/console.py)로 바꾼다.
- 앱 조작은 화면 탭(uiautomator로 글자를 찾아 누름), 내비 목적지는 verify_scene7과 같은 방법으로 내비 화면을 누른다.
- 모든 시각은 호스트 시계(epoch초). 앱 로그는 logcat -v epoch.

  python3 tools/record_demo.py test   recordings/demo_test     # 짧은 시험 녹화(혼자 노래방 → 문 열림 → 8초 뒤 노래방)
  python3 tools/record_demo.py main   recordings/demo_main     # 본 녹화(docs/시연순서.md 대본). 앱 데이터는 지우지 않는다(칭호 카드 때문)
  python3 tools/record_demo.py lyrics recordings/demo_lyrics   # 가사 싱크 확인(아리랑 처음부터 약 58초)

결과 폴더: screen_*.mp4, audio.wav, console/(프레임과 frames.tsv), logcat.txt, events.json. recordings/는 저장소에 넣지 않는다.
설정 「전국 랭킹에 올리기」를 끈 채로 녹화하면(기본값 끔) 서버에 문서가 생기지 않는다. 11/13 녹화 때 꺼져 있었다.
"""
import json
import os
import re
import signal
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
_argv, sys.argv = sys.argv, sys.argv[:1]     # verify_scene7·verify_scenes가 import 때 인자를 결과 폴더로 읽지 않게
import verify_scene7 as s7  # noqa: E402
sys.argv = _argv
vs = s7.vs
HERE = os.path.dirname(os.path.abspath(__file__))

USAGE = __doc__.split("\n\n")[2] if __doc__ else ""
# 콘솔 페이지(1600x1000)의 버튼 자리(BODY 칸의 문·창문, 오른쪽 위 기어 칸)
BTN = {"door_fr": (1475, 735), "door_fl": (1250, 735), "win_fl": (1205, 917),
       "gear_P": (1439, 501), "gear_R": (1473, 501), "gear_N": (1507, 501), "gear_D": (1541, 501)}
ZONES = ("front_left", "front_right", "rear_left", "rear_right")


class Rec:
    def __init__(self, out):
        self.out = out
        os.makedirs(out, exist_ok=True)
        self.events, self.segs, self.screen, self.t0 = [], [], None, None
        self.shift = 0.0                      # 앞 장면이 늦어진 만큼 뒤 일정을 미룬다

    def ev(self, kind, text="", **kw):
        e = {"t": time.time(), "kind": kind, "text": text, **kw}
        self.events.append(e)
        rel = e["t"] - self.t0 if self.t0 else 0.0
        print(f"[{rel:7.2f}] {kind}: {text}", flush=True)
        return e

    def sub(self, text, scene=""):
        return self.ev("sub", text, scene=scene)

    # ---- 녹화 시작·끝 ----
    def start(self):
        self.tab = subprocess.Popen(["node", os.path.join(HERE, "console_tab.js"), os.path.join(self.out, "console")],
                                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        line = self.tab.stdout.readline()
        if not line.startswith("ready"):
            raise SystemExit("콘솔 탭을 못 열었다: " + line)
        vs.sh("adb", "logcat", "-c")
        self.logf = open(os.path.join(self.out, "logcat.txt"), "w")
        self.logcat = subprocess.Popen(["adb", "logcat", "-v", "epoch", "-s", "SC.Gate:I", "SC.Navi:I", "SC.Tts:I",
                                        "SC.Mascot:I", "SC.Mixer:I", "SC.Stage:I"], stdout=self.logf,
                                       stderr=subprocess.DEVNULL)
        idx = qemu_stream()
        self.t_audio = time.time()
        self.audio = subprocess.Popen(["parecord", "--format=s16le", "--rate=48000", "--channels=2", "--latency-msec=20",
                                       f"--monitor-stream={idx}", os.path.join(self.out, "audio.wav")])
        self.t0 = time.time()
        self.ev("rec_start", f"qemu 스트림 {idx}")

    def stop(self):
        if self.screen:
            self.screen_stop()
        self.audio.send_signal(signal.SIGINT)
        self.audio.wait(timeout=10)
        self.ev("rec_stop")
        with open(os.path.join(self.out, "events.json"), "w") as f:
            json.dump({"t_audio": self.t_audio, "segs": self.segs, "events": self.events}, f, ensure_ascii=False, indent=1)
        self.logcat.terminate()
        self.logf.close()
        self.tab.stdin.write("quit\n")
        self.tab.stdin.flush()
        try:
            self.tab.wait(timeout=15)
        except subprocess.TimeoutExpired:      # 브라우저 닫기가 늦으면 끈다(프레임은 이미 저장됨)
            self.tab.kill()

    def screen_start(self, name):
        dev = f"/sdcard/{name}.mp4"
        vs.sh("adb", "shell", "rm", "-f", dev, check=False)
        t = time.time()
        p = subprocess.Popen(["adb", "shell", "screenrecord", "--size", "1920x1080", "--bit-rate", "16000000",
                              "--time-limit", "180", dev])
        self.screen = (name, dev, p, t)
        self.ev("screen_start", name)
        time.sleep(1.5)                     # 인코더가 첫 프레임을 낼 때까지

    def screen_stop(self):
        name, dev, p, t = self.screen
        vs.sh("adb", "shell", "pkill", "-INT", "screenrecord", check=False)
        p.wait(timeout=15)
        time.sleep(1.0)
        vs.sh("adb", "pull", dev, os.path.join(self.out, name + ".mp4"))
        self.segs.append({"name": name, "spawn": t, "stop": time.time()})
        self.ev("screen_stop", name)
        self.screen = None

    # ---- 조작 ----
    def click(self, key, label):
        x, y = BTN[key]
        self.tab.stdin.write(f"click {x} {y}\n")
        self.tab.stdin.flush()
        r = self.tab.stdout.readline().split()
        self.ev("console", label, at=float(r[1]) if len(r) > 1 else None)

    def http(self, label, *cmd):
        vs.c(*cmd)
        self.ev("console", label)

    def app(self, label, text):
        ok = vs.tap_text(text)
        self.ev("app", label, ok=ok)
        return ok

    def at(self, sec):
        """녹화 시작(t0)에서 sec초가 될 때까지 기다린다."""
        d = self.t0 + sec + self.shift - time.time()
        if d > 0:
            time.sleep(d)


def qemu_stream():
    out = subprocess.run(["pactl", "list", "short", "sink-inputs"], capture_output=True, text=True).stdout
    full = subprocess.run(["pactl", "list", "sink-inputs"], capture_output=True, text=True,
                          env={**os.environ, "LANG": "C"}).stdout
    for block in full.split("Sink Input #")[1:]:
        if "qemu" in block:
            return block.split()[0]
    raise SystemExit("qemu 소리 스트림이 없다(에뮬레이터가 소리를 내고 있는지 확인):\n" + out)


def car_base(gear="P"):
    for seat, v in (("driver", "on"), ("passenger", "off"), ("rear_left", "off"), ("rear_right", "off")):
        vs.c("seat", seat, v)
    for z in ZONES:
        vs.c("door", z, "close")
        vs.c("window", z, "0")
    vs.c("turn", "off")
    vs.c("wiper", "front", "Off")
    vs.c("gear", gear)


def open_app(wait=3):
    vs.sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{vs.PKG}/.MainActivity")
    time.sleep(wait)


def play_from_start():
    """예약 목록에서 아리랑을 누르면 처음부터 재생된다(make_readme_gif와 같은 방법)."""
    vs.tap_text("예약 목록")
    time.sleep(1.0)
    vs.tap_text("2021 아리랑 응원가")
    t = time.time()
    time.sleep(0.8)
    vs.tap_text("재생·가사")
    return t


def log_epoch(pattern, since=0.0):
    """logcat.txt 대신 지금 버퍼에서 epoch 시각으로 찾는다."""
    txt = vs.sh("adb", "logcat", "-d", "-v", "epoch", "-s", "SC.Gate:I", "SC.Navi:I", "SC.Tts:I")
    hits = [(float(m.group(1)), m.group(0)) for m in re.finditer(r"^\s*(\d+\.\d+)\s.*" + pattern + ".*$", txt, re.M)]
    return [h for h in hits if h[0] >= since]


def wait_log(pattern, timeout, since=0.0):
    t_end = time.time() + timeout
    while time.time() < t_end:
        h = log_epoch(pattern, since)
        if h:
            return h[0][0]
        time.sleep(0.25)
    return None


def ui_nodes():
    vs.sh("adb", "shell", "rm", "-f", "/sdcard/ui.xml", check=False)
    vs.sh("adb", "shell", "uiautomator", "dump", "--user", "10", "/sdcard/ui.xml", check=False)
    xml = vs.sh("adb", "shell", "cat", "/sdcard/ui.xml", check=False)
    out = []
    for node in re.findall(r"<node [^>]*>", xml):
        t = re.search(r'text="([^"]*)"', node)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if t and b and vs.PKG in node:
            out.append((t.group(1), tuple(map(int, b.groups()))))
    return out


def card_title():
    """덮개 카드가 떠 있으면 그 제목(칭호 이름 등), 없으면 None."""
    nodes = [t for t, _ in ui_nodes() if t]
    for i, t in enumerate(nodes):
        if t.startswith(vs.CARD_HEADS):
            return nodes[i + 1] if i + 1 < len(nodes) else t
    return None


def dismiss_cards_fast(r=None, show=0.0):
    """덮개 카드(칭호·미션·투어)가 있으면 「다음 카드 n장」만큼 카드의 확인 자리를 바로 누른다(덤프를 매번 하지 않아 빠르다)."""
    for _ in range(3):
        nodes = ui_nodes()
        if not any(t.startswith(vs.CARD_HEADS) for t, _ in nodes):
            return
        oks = [b for t, b in nodes if t == "확인" and b[1] > 400]      # 맨 위 오늘 요약 띠의 확인은 빼고
        if not oks:
            return
        more = [int(m.group(1)) for t, _ in nodes if (m := re.search(r"다음 카드 (\d+)장", t))]
        n = (more[0] if more else 0) + 1
        x1, y1, x2, y2 = oks[0]
        if show:
            time.sleep(show)
            show = 0.0
        for _ in range(n):
            vs.sh("adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            time.sleep(0.9)
        if r:
            r.ev("app", f"카드 확인 {n}번")


def queue_next_songs():
    """아리랑 뒤에 Destroy·Wade를 예약한다(아리랑만 있으면 곡이 끝나고 재생이 멈춘다).
    오른쪽 「이어서 나올 곡」 칸(x > 1700)에 두 곡이 보일 때까지 세 번까지 한다."""
    want = {"I Want To Destroy Something Beautiful", "Wade"}
    for _ in range(3):
        vs.tap_text("예약 목록")
        time.sleep(1.5)
        nodes = ui_nodes()
        if want <= {t for t, b in nodes if b[0] > 1700}:
            break
        rows = sorted([b for t, b in nodes if t == "예약"], key=lambda b: b[1])
        for x1, y1, x2, y2 in rows[1:3]:
            vs.sh("adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            time.sleep(0.8)
        time.sleep(0.5)
        if want <= {t for t, b in ui_nodes() if b[0] > 1700}:
            break
    queued = sorted({t for t, b in ui_nodes() if b[0] > 1700 and t in want})
    vs.tap_text("재생·가사")
    return queued


def prep_karaoke(clear):
    """주차·혼자·문 닫힘에서 아리랑을 처음부터 틀고 노래방이 될 때까지. 재생을 누른 호스트 시각을 돌려준다."""
    car_base("P")
    if clear:
        vs.sh("adb", "shell", "pm", "clear", "--user", "10", vs.PKG, check=False)
    vs.sh("adb", "shell", "am", "force-stop", "--user", "10", vs.PKG)
    vs.sh("adb", "logcat", "-c")
    open_app(5)
    vs.dismiss_cards()
    vs.tap_text("확인")               # 오늘 요약 띠가 있으면 닫기(없으면 아무것도 안 함)
    t_play = play_from_start()
    queued = queue_next_songs()
    print("예약:", queued)
    if len(queued) < 2:
        raise SystemExit("다음 곡 예약을 못 했다(아리랑이 끝나면 재생이 멈춘다)")
    if vs.wait_state("KARAOKE", 12) is None:
        raise SystemExit("노래방이 되지 않았다")
    time.sleep(1.5)
    dismiss_cards_fast()          # 노래방이 되면서 받은 칭호 카드가 무대를 덮지 않게 녹화 전에 닫는다
    vs.tap_text("확인")
    return t_play


# ---------------- 시나리오 ----------------
def run_test(out):
    t_play = prep_karaoke(clear=False)
    r = Rec(out)
    while time.time() < t_play + 25.0:
        time.sleep(0.05)
    r.start()
    r.screen_start("screen_a")
    r.ev("part_start")
    r.sub("시험: 혼자만의 노래방", "시험")
    r.at(4.5)
    r.click("door_fr", "동승석 문 열기")
    r.sub("문 열림 → 가수 목소리 복귀", "시험")
    r.at(8.0)
    r.click("door_fr", "동승석 문 닫기")
    r.sub("8초 뒤 다시 노래방", "시험")
    r.at(19.0)
    r.ev("part_end")
    r.stop()


def run_main(out):
    print("내비 첫 화면:", s7.reset_navi())
    # pm clear를 하면 첫 노래방에서 「첫 무대」 칭호 카드가 떠 무대를 덮는다. 칭호를 이미 받은 지금 기록을 그대로 쓴다.
    t_play = prep_karaoke(clear=False)
    r = Rec(out)
    # 아리랑 셋째 줄(31.23초 "나를 버리고 가시는 님은")이 시작될 때 문을 연다 → 녹화는 곡 27초부터
    while time.time() < t_play + 26.8:
        time.sleep(0.05)
    r.start()
    r.screen_start("screen_a")
    r.ev("part_start")
    r.sub("혼자 탄 차(주차 중) — 가수 목소리를 줄인 「혼자만의 노래방」", "① 혼자만의 노래방")
    r.at(4.0)
    r.click("door_fr", "동승석 문 열기")
    r.sub("동승석 문이 열리는 순간 → 가수 목소리 복귀, 평범한 음악 앱으로", "② 들킬 뻔 → 시치미")
    r.at(10.0)
    r.sub("시치미 — 혼자일 때만 열리는 차 안 노래방", "② 들킬 뻔 → 시치미")
    r.at(13.0)
    r.http("동승석 착석", "seat", "passenger", "on")
    r.sub("동승자가 타면: 로고·한밤 무대 틀·내 기록·점수 탭이 모두 숨는다", "③ 동승자 화면")
    r.at(14.2)
    r.click("door_fr", "동승석 문 닫기")
    r.at(16.0)
    r.app("예약 목록 탭", "예약 목록")
    r.at(19.5)
    r.app("재생·가사 탭", "재생·가사")
    r.at(23.0)
    r.http("동승석 비움", "seat", "passenger", "off")
    r.click("door_fr", "동승석 문 열기")
    r.sub("동승자가 내리고 문이 닫히면 → 다시 혼자", "④ 다시 혼자")
    r.at(25.0)
    r.click("door_fr", "동승석 문 닫기")
    r.sub("들킬 뻔 직후라 5초가 아니라 8초 뒤에 노래방", "④ 다시 혼자")
    t = vs.wait_state("KARAOKE", 12)
    r.ev("state", "KARAOKE", ok=t is not None)
    title = card_title()
    if title:      # 들킬 뻔을 넘긴 횟수 등으로 칭호를 받으면 카드가 무대를 덮는다. 보여 주고 닫는다.
        r.sub(f"다시 노래방 — 들킬 뻔을 넘겨 새 칭호 「{title}」", "④ 다시 혼자")
        dismiss_cards_fast(r, show=2.0)
        time.sleep(0.5)
    r.sub("로고와 한밤 무대 틀이 돌아온다", "④ 다시 혼자")
    r.shift = max(0.0, time.time() + 3.5 - (r.t0 + 37.5))
    r.at(37.5)
    r.click("gear_D", "기어 D")
    r.sub("주행 중 — 운전을 방해하지 않는 큰 버튼 화면, 노래방은 그대로", "⑤ 주행")
    r.at(42.0)
    r.click("win_fl", "운전석 창문 열기")
    r.sub("운전석 창문을 열어도 → 가수 목소리 복귀", "⑥ 창문")
    r.at(46.0)
    r.click("win_fl", "운전석 창문 닫기")
    r.sub("창문을 닫으면 8초 뒤 다시 노래방", "⑥ 창문")
    t = vs.wait_state("KARAOKE", 12)
    r.ev("state", "KARAOKE", ok=t is not None)
    r.at(56.5)
    r.click("gear_R", "기어 R")
    r.sub("후진하는 동안은 잠깐 가수 복귀", "⑦ 후진")
    r.at(60.0)
    r.click("gear_D", "기어 D")
    r.sub("D로 돌아오면 1.5초 뒤 다시 노래방", "⑦ 후진")
    t = vs.wait_state("KARAOKE", 6)
    r.ev("state", "KARAOKE", ok=t is not None)
    r.at(64.0)
    r.sub("내비에 3분 거리 목적지를 넣으면…", "⑧ 도착 3분 전")
    since = time.time()
    r.ev("app", f"내비 목적지 '{s7.DEST}'")
    ok = s7.set_destination()
    r.ev("app", "길 안내 누름", ok=ok)
    t_say = wait_log("말하기 시작", 20, since)
    if t_say:
        r.events.append({"t": t_say, "kind": "sub", "text": "「도착까지 3분, 한 곡 더 됩니다」 (노래방일 때만 말한다)",
                         "scene": "⑧ 도착 3분 전"})
        print("말하기 시작", t_say - r.t0)
    t_end = wait_log("말하기 끝", 20, since)
    recv = log_epoch(r"남은 시간 \S+ 정보: [1-9]\d*초", since)
    remain = int(re.search(r"(\d+)초", recv[0][1]).group(1)) if recv else 180
    t_recv = recv[0][0] if recv else time.time()
    time.sleep(1.0)
    open_app(0)
    r.ev("app", "앱으로 돌아옴")
    time.sleep(4.0)
    r.ev("part_end")
    r.screen_stop()
    due = t_recv + remain - 60
    r.ev("info", f"남은 시간 {remain}초 수신 {t_recv - r.t0:.1f}s, 곧 도착 예정 {due - r.t0:.1f}s, 말하기 끝 {(t_end or 0) - r.t0:.1f}s")
    while time.time() < due - 13.0:
        time.sleep(0.2)
    r.screen_start("screen_b")
    r.ev("part_start")
    r.sub("(2분 뒤) 도착 1분 전 — 차가 서기 전에 미리 시치미", "⑨ 도착 1분 전")
    t_ret = wait_log("상태 KARAOKE -> RETURN", 30, due - 20)
    r.ev("state", "RETURN", ok=t_ret is not None, log_t=t_ret)
    time.sleep(0.3)
    r.sub("「잠시 가수 복귀」 — 내리기 전에 가수 목소리가 먼저 돌아온다", "⑨ 도착 1분 전")
    time.sleep(6.5)
    r.sub("도착 → 안내 종료, 기어 P: 오늘 몰래 부른 기록", "⑩ 오늘 기록")
    s7.open_navi(2.5)
    b = s7.bounds_of("안내 종료")
    if b:
        s7.tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, 1.5)
    else:
        s7.tap(1216, 1224, 1.5)
    r.ev("app", "내비 안내 종료")
    open_app(1.5)
    r.click("gear_P", "기어 P")
    time.sleep(2.0)
    dismiss_cards_fast(r, show=2.5)
    r.app("내 기록 탭", "내 기록")
    time.sleep(7.0)
    r.sub("시치미 — 들키지 않는 나만의 무대", "끝")
    time.sleep(4.0)
    r.ev("part_end")
    r.stop()


def run_lyrics(out):
    car_base("P")
    vs.sh("adb", "shell", "am", "force-stop", "--user", "10", vs.PKG)
    open_app(5)
    vs.dismiss_cards()
    vs.tap_text("확인")
    vs.tap_text("예약 목록")
    time.sleep(1.0)
    r = Rec(out)
    r.start()
    r.screen_start("screen_a")
    vs.tap_text("2021 아리랑 응원가")
    t_play = r.ev("app", "아리랑 처음부터 재생")["t"]
    r.ev("part_start")
    r.sub("가사 싱크 확인 — 2021 아리랑 응원가 (주차·혼자 → 5초 뒤 혼자만의 노래방 무대)", "가사")
    time.sleep(0.8)
    r.app("재생·가사 탭", "재생·가사")
    t = vs.wait_state("KARAOKE", 12)
    r.ev("state", "KARAOKE", ok=t is not None)
    time.sleep(1.0)
    dismiss_cards_fast(r)
    while time.time() < t_play + 58.5:
        time.sleep(0.1)
    r.ev("part_end")
    r.stop()


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("test", "main", "lyrics") or sys.argv[2].startswith("-"):
        print(USAGE)
        sys.exit(0 if any(a in ("-h", "--help") for a in sys.argv[1:]) else 2)
    {"test": run_test, "main": run_main, "lyrics": run_lyrics}[sys.argv[1]](sys.argv[2])


if __name__ == "__main__":
    main()
