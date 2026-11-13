#!/usr/bin/env python3
"""시연 영상 녹화(11/13). 에뮬레이터 화면·소리·콘솔 탭·이벤트 시각을 따로 담고, 합치기는 compose_demo.py가 한다.

- 에뮬레이터 화면: adb shell screenrecord(본 녹화 1280x720 6Mbps, 가사 1920x1080 16Mbps, 한 조각 180초까지). 조각 시작 시각은 합칠 때
  문지기 로그와 화면 변화로 맞춘다. screenrecord는 에뮬레이터 안 소프트웨어 인코더라 CPU를 많이 쓴다. 11/13 첫 본 녹화(1080p)에서
  후진(후방 카메라 앱이 같이 돎)과 내비 안내 음성 때 호스트 CPU가 모자라 소리가 끊겼다 → 본 녹화는 크기를 줄인다(합칠 때 1440x810으로 줄이므로 손해 없음).
- 콘솔 탭(헤드리스 크로미움)은 nice 10으로 띄워 에뮬레이터 소리 스레드에 CPU를 양보한다.
- 본 녹화 전에 내비 경로 안내와 TTS를 한 번 돌려 둔다(처음 돌 때 무거워 소리가 끊겼다).
- 소리: parecord --monitor-stream으로 qemu 출력 스트림만 받는다(호스트 스피커 음량·음소거와 상관없고 다른 소리가 섞이지 않는다).
- 콘솔 탭: tools/console_tab.js가 8088 콘솔 페이지를 헤드리스로 열어 프레임을 저장하고, 문·창문·기어는 그 페이지의 버튼을 실제로 누른다.
  좌석(착석)은 콘솔 페이지 첫 화면에 버튼이 없어 콘솔 페이지와 같은 HTTP 경로(tools/console.py)로 바꾼다.
- 앱 조작은 화면 탭(uiautomator로 글자를 찾아 누름), 내비 목적지는 verify_scene7과 같은 방법으로 내비 화면을 누른다.
- 모든 시각은 호스트 시계(epoch초). 앱 로그는 logcat -v epoch.

  python3 tools/record_demo.py test   recordings/demo_test     # 짧은 시험 녹화(혼자 노래방 → 문 열림 → 8초 뒤 노래방)
  python3 tools/record_demo.py main   recordings/demo_main     # 본 녹화(docs/시연순서.md 대본). 앱 데이터는 지우지 않는다(칭호 카드 때문)
  python3 tools/record_demo.py lyrics recordings/demo_lyrics   # 가사 싱크 확인(아리랑 처음부터 약 58초)
  python3 tools/record_demo.py score  recordings/demo_score [곡 id] [--test 초|full] [--voice none] [--out 영상] [--sing 초]
      # 노래 점수(마이크): 사람이 직접 부른다. Enter를 치면 곡이 처음부터 나오고, 끝나면 기어 P → 점수 카드 → 「점수」 탭까지 찍고
      # compose_demo.py --mic로 docs/media/demo_score.mp4를 만든다. 곡 id 기본값 03-wade(가장 짧음, 3분 5초).
      # --test 초: 사람 없이 시험(가상 마이크 sichimi_mic에 원곡 보컬을 흘리고 그 초에서 멈춤, 영상은 <폴더>/test.mp4)
      # --test full --voice none: 곡 끝까지(두 조각으로 찍기·곡 끝 처리 확인). 가상 마이크가 완전한 0이라 앱은 채점하지 않는다(점수 기록 안 남음)

결과 폴더: screen_*.mp4, audio.wav, (score) mic.wav, console/(프레임과 frames.tsv), logcat.txt, events.json. recordings/는 저장소에 넣지 않는다.
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
        self.screen_opts = ("1920x1080", "16000000")
        self.log_tags = ["SC.Gate:I", "SC.Navi:I", "SC.Tts:I", "SC.Mascot:I", "SC.Mixer:I", "SC.Stage:I"]
        self.mic_src = None                   # score: 사람 목소리를 따로 담을 PulseAudio 소스(호스트 기본 입력)
        self.meta = {}                        # events.json에 더 적을 것

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
        self.tab = subprocess.Popen(["env", "SCREENCAST_NTH=2", "nice", "-n", "10", "node", os.path.join(HERE, "console_tab.js"), os.path.join(self.out, "console")],
                                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        line = self.tab.stdout.readline()
        if not line.startswith("ready"):
            raise SystemExit("콘솔 탭을 못 열었다: " + line)
        vs.sh("adb", "logcat", "-c")
        self.logf = open(os.path.join(self.out, "logcat.txt"), "w")
        self.logcat = subprocess.Popen(["adb", "logcat", "-v", "epoch", "-s", *self.log_tags], stdout=self.logf,
                                       stderr=subprocess.DEVNULL)
        idx = qemu_stream()
        self.mic = None
        if self.mic_src:
            self.meta["mic"] = {"src": self.mic_src, "t": time.time()}
            self.mic = subprocess.Popen(["parecord", f"--device={self.mic_src}", "--format=s16le", "--rate=48000",
                                         "--channels=1", "--latency-msec=20", os.path.join(self.out, "mic.wav")])
        self.t_audio = time.time()
        self.audio = subprocess.Popen(["parecord", "--format=s16le", "--rate=48000", "--channels=2", "--latency-msec=20",
                                       f"--monitor-stream={idx}", os.path.join(self.out, "audio.wav")])
        self.t0 = time.time()
        self.ev("rec_start", f"qemu 스트림 {idx}")

    def stop(self):
        if self.screen:
            self.screen_stop()
        for p in (self.audio, self.mic):
            if p:
                p.send_signal(signal.SIGINT)
                p.wait(timeout=10)
        self.ev("rec_stop")
        with open(os.path.join(self.out, "events.json"), "w") as f:
            json.dump({**self.meta, "t_audio": self.t_audio, "segs": self.segs, "events": self.events}, f,
                      ensure_ascii=False, indent=1)
        self.logcat.terminate()
        self.logf.close()
        self.tab.stdin.write("quit\n")
        self.tab.stdin.flush()
        try:
            self.tab.wait(timeout=15)
        except subprocess.TimeoutExpired:      # 브라우저 닫기가 늦으면 끈다(프레임은 이미 저장됨)
            self.tab.kill()

    def screen_start(self, name):
        size, rate = self.screen_opts
        dev = f"/sdcard/{name}.mp4"
        vs.sh("adb", "shell", "rm", "-f", dev, check=False)
        t = time.time()
        p = subprocess.Popen(["adb", "shell", "screenrecord", "--size", size, "--bit-rate", rate,
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


def log_epoch(pattern, since=0.0, tags=("SC.Gate:I", "SC.Navi:I", "SC.Tts:I")):
    """logcat.txt 대신 지금 버퍼에서 epoch 시각으로 찾는다."""
    txt = vs.sh("adb", "logcat", "-d", "-v", "epoch", "-s", *tags)
    hits = [(float(m.group(1)), m.group(0)) for m in re.finditer(r"^\s*(\d+\.\d+)\s.*" + pattern + ".*$", txt, re.M)]
    return [h for h in hits if h[0] >= since]


def wait_log(pattern, timeout, since=0.0, tags=("SC.Gate:I", "SC.Navi:I", "SC.Tts:I")):
    t_end = time.time() + timeout
    while time.time() < t_end:
        h = log_epoch(pattern, since, tags)
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


def warm_navi_tts():
    """본 녹화 전에 노래방에서 3분 목적지로 길 안내를 한 번 시작해 「도착까지 3분」을 말하게 하고 안내를 끝낸다.
    내비 경로 계산과 TTS 엔진이 처음 돌 때 무거워 녹화 중 소리가 끊겼다(11/13 첫 본 녹화 1:25 근처)."""
    try:
        prep_karaoke(clear=False)
    except SystemExit as e:              # 다음 곡 예약이 안 들어가도 미리 돌리기는 한다
        print("미리 돌리기 준비:", e)
    since = time.time()
    ok = s7.set_destination()
    said = wait_log("말하기 끝", 30, since) if ok else None
    s7.open_navi(2.5)
    b = s7.bounds_of("안내 종료")
    if b:
        s7.tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, 1.5)
    print("미리 돌리기: 길 안내", ok, "말하기", said is not None)
    vs.c("gear", "P")


# 내비 남은 시간은 시간대에 따라 바뀐다. 더몰트하우스 서핑시티(330m)는 낮 3분·밤 2분이라 밤에는 「한 곡 더」(3~5분) 밖이다.
# 영상은 3~5분 한가운데인 곳을 쓴다(11/14 0시에 4분, 1.2km).
DEMO_DEST = os.environ.get("DEMO_DEST", "뚜레쥬르 판교위든타워점")


def run_main(out):
    s7.DEST = DEMO_DEST
    print("내비 첫 화면:", s7.reset_navi())
    warm_navi_tts()
    print("내비 첫 화면:", s7.reset_navi())
    # pm clear를 하면 첫 노래방에서 「첫 무대」 칭호 카드가 떠 무대를 덮는다. 칭호를 이미 받은 지금 기록을 그대로 쓴다.
    t_play = prep_karaoke(clear=False)
    r = Rec(out)
    r.screen_opts = (os.environ.get("DEMO_SCREEN", "1280x720"), "6000000")
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
    r.sub("내비에 도착까지 3~5분 남은 목적지를 넣으면…", "⑧ 도착 3~5분 전")
    since = time.time()
    r.ev("app", f"내비 목적지 '{s7.DEST}'")
    ok = s7.set_destination()
    r.ev("app", "길 안내 누름", ok=ok)
    t_say = wait_log("말하기 시작", 20, since)
    if t_say:
        said = log_epoch(r"말하기 요청: (.+)", since)
        line = re.search(r"말하기 요청: (.+)", said[-1][1]).group(1).strip() if said else "도착까지 N분, 한 곡 더 됩니다"
        r.events.append({"t": t_say, "kind": "sub", "text": f"「{line}」 (노래방일 때만 말한다)",
                         "scene": "⑧ 도착 3~5분 전"})
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
    gap_min = max(1, round((time.time() + 1.5 - r.events[-1]["t"]) / 60))   # 잘라 낸 시간(분)
    r.screen_start("screen_b")
    r.ev("part_start")
    r.sub(f"({gap_min}분 뒤) 도착 1분 전 — 차가 서기 전에 미리 시치미", "⑨ 도착 1분 전")
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


# ---------------- 노래 점수(마이크, 11/14) ----------------
# 사람이 이어폰을 끼고 직접 부른다. 에뮬레이터(qemu)는 호스트 기본 입력을 앱 마이크로 쓰고, 같은 입력을 호스트에서 따로 담아(mic.wav)
# 합칠 때 반주(qemu 출력)에 섞는다. 시험(--test)은 사람 대신 가상 장치 sichimi_mic에 원곡 보컬을 흘린다(verify_g5와 같은 방식).
VMIC = "sichimi_mic"
ROOT = os.path.dirname(HERE)
SCORE_TAGS = ["SC.Score:I", "SC.Mic:I", "SC.Player:I"]
HEARD_LAG = 0.45            # 재생을 누른 로그 → 그 소리가 qemu 출력(이어폰)에 나오기까지(검증기록 G2, 시연 영상 절의 0.44~0.47초)
COMPOSE_PY = os.path.expanduser("~/.venvs/demucs/bin/python")


def pa(*a):
    return subprocess.run(["pactl", *a], capture_output=True, text=True, env={**os.environ, "LANG": "C", "LC_ALL": "C"}).stdout


def qemu_mic():
    """qemu 마이크 입력(source-output) 번호와 지금 붙어 있는 소스 이름."""
    names = {}
    for line in pa("list", "short", "sources").splitlines():
        f = line.split("\t")
        if len(f) > 1:
            names[f[0]] = f[1]
    for block in pa("list", "source-outputs").split("Source Output #")[1:]:
        if "qemu" in block:
            m = re.search(r"^\s*Source: (\d+)", block, re.M)
            return block.split()[0], names.get(m.group(1), m.group(1)) if m else "?"
    raise SystemExit("qemu 마이크 입력이 없다(에뮬레이터가 켜져 있는지 확인)")


def route_mic(test):
    """qemu 마이크 입력을 시험이면 가상 장치 모니터로, 아니면 호스트 기본 입력으로 옮기고 hostmicon을 켠다.
    따로 담을 소스 이름(앱이 듣는 것과 같은 소리)을 돌려준다."""
    if test:
        if VMIC not in pa("list", "short", "sinks"):
            subprocess.run(["pactl", "load-module", "module-null-sink", f"sink_name={VMIC}"], check=True, capture_output=True)
        src = VMIC + ".monitor"
    else:
        src = pa("get-default-source").strip()
        if src.startswith(VMIC):
            raise SystemExit(f"호스트 기본 입력이 시험용 가상 장치({src})다. 설정 → 소리 → 입력에서 마이크(이어폰 마이크)를 고른다")
    idx, now = qemu_mic()
    if now != src:
        subprocess.run(["pactl", "move-source-output", idx, src], check=True)
        print(f"qemu 마이크 입력 {idx}: {now} → {src}")
    vs.sh("adb", "emu", "avd", "hostmicon", check=False)
    idx, now = qemu_mic()
    if now != src:
        raise SystemExit(f"qemu 마이크 입력을 {src}로 옮기지 못했다(지금 {now})")
    return src


def restore_mic():
    """시험 뒤: qemu 마이크 입력을 호스트 기본 입력으로 되돌린다(가상 장치는 그대로 둔다)."""
    idx, now = qemu_mic()
    src = pa("get-default-source").strip()
    if now != src and not src.startswith(VMIC):
        subprocess.run(["pactl", "move-source-output", idx, src], check=False)
        print(f"qemu 마이크 입력 {idx}: {now} → {src}(되돌림)")


def level_db(src, sec=2.5):
    """소스에서 sec초 받아 RMS(dBFS). parecord는 지연 버퍼를 작게(20ms) 하지 않으면 짧게 받을 때 아무것도 쓰지 않는다."""
    import array
    import math
    import tempfile
    import wave
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "level.wav")
        subprocess.run(["timeout", "-s", "INT", str(sec), "parecord", f"--device={src}", "--format=s16le", "--rate=48000",
                        "--channels=1", "--latency-msec=20", path], capture_output=True)
        try:
            with wave.open(path) as w:
                a = array.array("h", w.readframes(w.getnframes()))
        except (OSError, EOFError, wave.Error):
            return None
    a = a[len(a) // 5:]                     # 여는 순간의 0은 빼고
    if not a:
        return None
    return 20 * math.log10(math.sqrt(sum(x * x for x in a) / len(a)) / 32768 + 1e-9)


def host_audio_report(src):
    sink = pa("get-default-sink").strip()
    vol = re.findall(r"(\d+)%", pa("get-sink-volume", sink))
    mute = pa("get-sink-mute", sink).strip()
    print(f"  듣는 곳(호스트 기본 출력): {sink}  음량 {'/'.join(vol) or '?'}%  {mute}")
    print(f"  마이크(호스트 기본 입력): {src}")
    db = level_db(src)
    print(f"  마이크 2초 크기: {'?' if db is None else f'{db:.0f} dBFS'}" +
          ("  ← 거의 0이다. 마이크가 꺼졌거나 음소거인지 확인" if db is not None and db < -75 else ""))
    if "yes" in mute or (vol and int(vol[0]) < 10):
        print("  ! 호스트 출력이 음소거이거나 아주 작다. 이어폰으로 반주가 들리도록 음량을 올린다(녹화는 qemu 출력을 직접 받아 상관없음)")


def song_meta(song_id):
    meta = json.load(open(os.path.join(ROOT, "songs", song_id, "meta.json"), encoding="utf-8"))
    lrc = []
    path = os.path.join(ROOT, "songs", song_id, "lyrics.lrc")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            for m in re.finditer(r"\[(\d+):(\d+(?:\.\d+)?)\]", line):
                text = re.sub(r"\[[^\]]*\]", "", line).strip()
                if text:
                    lrc.append((int(m.group(1)) * 60 + float(m.group(2)), text))
    return meta, sorted(lrc)


def node_center(label):
    """글자나 이름(content-desc)이 label인 앱 노드의 가운데."""
    vs.sh("adb", "shell", "rm", "-f", "/sdcard/ui.xml", check=False)
    vs.sh("adb", "shell", "uiautomator", "dump", "--user", "10", "/sdcard/ui.xml", check=False)
    xml = vs.sh("adb", "shell", "cat", "/sdcard/ui.xml", check=False)
    for node in re.findall(r"<node [^>]*>", xml):
        if vs.PKG in node and re.search(r'(?:text|content-desc)="%s"' % re.escape(label), node):
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            if b:
                x1, y1, x2, y2 = map(int, b.groups())
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def tap_xy(xy):
    vs.sh("adb", "shell", "input", "tap", str(xy[0]), str(xy[1]))


def prep_score(title):
    """설정(노래 점수 켬, 전국 랭킹에 올리기 끔) → 주차·혼자에서 곡을 틀어 노래방이 되면 멈추고 → 기어 D.
    노래방은 일시정지해도 그대로라, D에서 「이전 곡」(처음으로)·「재생」만 누르면 곡이 처음부터 나온다."""
    import verify_g5 as g5
    g5.set_switch("노래 점수(마이크)", True)
    try:
        g5.set_switch("전국 랭킹에 올리기", False)
    except SystemExit as e:
        print("전국 랭킹 스위치:", e)
    car_base("P")
    vs.sh("adb", "shell", "am", "force-stop", "--user", "10", vs.PKG)
    vs.sh("adb", "logcat", "-c")
    open_app(5)
    vs.dismiss_cards()
    vs.tap_text("확인")
    vs.tap_text("예약 목록")
    time.sleep(1.0)
    if not vs.tap_text(title):                 # 예약 목록에서 누르면 대기열이 이 곡 하나가 된다(끝나면 멈춤)
        raise SystemExit(f"예약 목록에서 '{title}'을 못 찾았다")
    time.sleep(0.8)
    vs.tap_text("재생·가사")
    if vs.wait_state("KARAOKE", 12) is None:
        raise SystemExit("노래방이 되지 않았다")
    vs.tap_text("일시정지")
    time.sleep(1.0)
    dismiss_cards_fast()
    vs.tap_text("확인")
    vs.c("gear", "D")
    time.sleep(2.5)
    if vs.last_state() != "KARAOKE":
        raise SystemExit(f"기어 D에서 노래방이 아니다({vs.last_state()})")
    prev, play = node_center("이전 곡"), node_center("재생")
    if not prev or not play:
        raise SystemExit(f"주행 화면 버튼을 못 찾았다(이전 곡 {prev}, 재생 {play})")
    return prev, play


def prompter(lrc, t_zero, stop):
    """주행 화면에는 가사가 없어서 터미널에 가사를 띄운다(들리는 시각보다 0.6초 먼저)."""
    for i, (t, text) in enumerate(lrc):
        while not stop() and time.time() < t_zero + t - 0.6:
            time.sleep(0.05)
        if stop():
            return
        nxt = lrc[i + 1][1] if i + 1 < len(lrc) else ""
        print(f"  ♪ {text}" + (f"      (다음: {nxt})" if nxt else ""), flush=True)


def score_line(since):
    """SC.Score 「점수 …」 줄과 지연 가정별 점수."""
    h = log_epoch(r"점수 (곡 끝|곡 바뀜|앞으로 되감음): .+", since, ("SC.Score:I",))
    probe = log_epoch(r"지연 가정별 점수: .+", since, ("SC.Score:I",))
    return (h[-1] if h else None), (probe[-1][1] if probe else None)


def run_score(out, song_id, test=None, out_mp4=None, sing=15.0, voice=True):
    """test: None(사람이 부름) · 초(가상 마이크, 그 초에서 멈춤) · "full"(가상 마이크, 곡 끝까지). voice=False면 가상 마이크에 아무것도 흘리지 않는다."""
    import threading
    meta, lrc = song_meta(song_id)
    title, dur = meta["title"], meta["durationMs"] / 1000
    vocal_at = meta.get("countIn", {}).get("vocalAtMs", 0) / 1000
    test_sec = test if isinstance(test, float) else None
    test = test is not None
    src = route_mic(test)
    prev, play = prep_score(title)
    voice_wav = None
    if test and voice:
        os.makedirs(out, exist_ok=True)
        voice_wav = os.path.join(out, "test_voice.wav")     # mix5.ogg의 보컬 두 채널(곡 시각과 정확히 같음)
        vs.sh("ffmpeg", "-v", "error", "-y", "-i", os.path.join(ROOT, "songs", song_id, "mix5.ogg"),
              "-af", "pan=stereo|c0=c3|c1=c4", "-ar", "48000", voice_wav)
    print(f"\n곡: {title} ({song_id}, {dur:.0f}초). 기어 D, 혼자만의 노래방(일시정지).")
    host_audio_report(src)
    if not test:
        input("\n이어폰을 끼고 준비되면 Enter — 곡이 처음부터 나온다(끝나면 알아서 멈춤, 그만두려면 Ctrl+C)\n")
    r = Rec(out)
    r.screen_opts = (os.environ.get("DEMO_SCREEN", "1280x720"), "6000000")
    r.log_tags += SCORE_TAGS
    r.mic_src = src
    r.meta = {"mode": "score", "song": song_id, "test": test, "test_sec": test_sec, "test_voice": bool(voice_wav)}
    stop_prompt = False
    try:
        r.start()
        r.screen_start("screen_a")
        tap_xy(prev)                       # 처음으로(일시정지 중 3초 넘게 지났으므로 이전 곡이 아니라 곡 처음)
        time.sleep(0.4)
        tap_xy(play)
        t_tap = time.time()
        t_log = wait_log(r"playWhenReady=true", 5, t_tap - 1.0, ("SC.Player:I",)) or t_tap
        t_zero = t_log + HEARD_LAG          # 곡 0초가 들리는 때(어림. 합칠 때 녹음에서 다시 잰다)
        r.ev("song_start", title, song=song_id, t_tap=t_tap, t_log=t_log, t_heard_guess=t_zero)
        player = None
        if voice_wav:                       # 사람이 들리는 반주에 맞춰 부르는 셈
            time.sleep(max(0.0, t_zero - time.time()))
            player = subprocess.Popen(["paplay", f"--device={VMIC}", voice_wav])
            r.ev("test_voice", "원곡 보컬을 가상 마이크로", t_spawn=time.time())
        th = threading.Thread(target=prompter, args=(lrc, t_zero, lambda: stop_prompt), daemon=True)
        th.start()
        t_end = t_zero + (test_sec or dur)
        p_start = max(time.time() + 0.2, t_zero + max(0.0, vocal_at - 3.0))
        p_end = p_start + sing
        split = t_end - p_end > 25.0
        time.sleep(max(0.0, p_start - time.time()))
        r.ev("part_start")
        r.sub("혼자 탄 차(주행 중) — 「노래 점수」를 켜면 마이크로 듣고 100점 만점으로 매긴다", "노래 점수")
        time.sleep(max(0.0, min(p_end, t_end) - 7.0 - time.time()))
        r.sub("소리는 저장하지 않고 음높이만 듣는다 · 혼자만의 노래방일 때만 마이크가 켜진다", "노래 점수")
        if split:
            time.sleep(max(0.0, p_end - time.time()))
            r.ev("part_end")
            r.screen_stop()
            time.sleep(max(0.0, t_end - 13.0 - time.time()))
            gap_min = max(1, round((time.time() + 1.5 - p_end) / 60))
            r.screen_start("screen_b")
            r.ev("part_start")
            r.sub(f"({gap_min}분 뒤) 곡 끝까지 직접 부르면…", "노래 점수")
        since = t_tap
        if test_sec:
            time.sleep(max(0.0, t_end - time.time()))
            tap_xy(play)                    # 일시정지 → 처음으로: 앱이 이 판을 마무리한다(앞으로 되감음)
            time.sleep(0.4)
            tap_xy(prev)
            r.ev("app", "시험 끝: 일시정지 · 처음으로")
            if player:
                player.terminate()
            hit = wait_log(r"점수 (곡 끝|곡 바뀜|앞으로 되감음)", 6, since, ("SC.Score:I",))
        else:
            hit = wait_log(r"점수 (곡 끝|곡 바뀜|앞으로 되감음)", max(20.0, t_end + 15 - time.time()), since, ("SC.Score:I",))
        stop_prompt = True
        ms = vs.sh("adb", "shell", "dumpsys", "media_session", check=False)
        st = re.findall(r"state=PlaybackState \{state=(\w+)\(\d+\), position=(\d+)", ms.split("package=" + vs.PKG, 1)[-1])
        if st:                              # 곡 끝 판정(멈춤이고 위치가 곡 길이 − 1.5초 넘음)을 볼 수 있게 남긴다
            r.ev("info", f"재생 상태 state={st[0][0]} 위치 {int(st[0][1]) / 1000:.1f}초 / 곡 {dur:.1f}초")
        line, probe = score_line(since)
        r.ev("score", line[1] if line else "점수 없음", log_t=hit)
        if probe:
            r.ev("info", probe)
        if line and "완주=true" in line[1]:
            t_say = wait_log("말하기 시작", 8, hit, ("SC.Tts:I",))
            said = log_epoch(r"말하기 요청: (.+)", hit - 1, ("SC.Tts:I",))
            if t_say and said:
                text = re.search(r"말하기 요청: (.+)", said[-1][1]).group(1).strip()
                r.events.append({"t": t_say, "kind": "sub", "text": f"곡이 끝나면 음성 한 줄 「{text}」", "scene": "노래 점수"})
                print("말하기:", text)
            wait_log("말하기 끝", 12, hit, ("SC.Tts:I",))
            time.sleep(1.0)
        else:
            print("! 완주가 아니라(곡 음표의 절반 미만) 점수 음성이 없다" if line else "! 점수가 남지 않았다(SC.Score 줄 없음)")
            time.sleep(1.5)
        r.click("gear_P", "기어 P")
        r.sub("주차하면 방금 부른 점수 카드", "노래 점수")
        time.sleep(2.0)
        dismiss_cards_fast(r, show=3.5)
        r.app("점수 탭", "점수")
        r.sub("혼자 부르면 점수 — 지난번의 나와 대결", "노래 점수")
        t_tab = time.time()
        for _ in range(8):                  # 전국 랭킹(서버에서 읽기만 함)이 「불러오는 중…」이면 조금 더 기다린다
            if not any("불러오는 중" in t for t, _ in ui_nodes()):
                break
            time.sleep(1.0)
        time.sleep(max(2.5, t_tab + 7.0 - time.time()))
        r.ev("part_end")
    except KeyboardInterrupt:
        stop_prompt = True
        print("\n멈춤(Ctrl+C). 담은 것은 저장하고 영상은 만들지 않는다.")
        r.stop()
        if test:
            restore_mic()
        sys.exit(1)
    r.stop()
    if test:
        restore_mic()
    print(f"\n녹화 {r.events[-1]['t'] - r.t0:.1f}초. 앱 로그:")
    for e in r.events:
        if e["kind"] in ("score", "info"):
            print(" ", e["text"])
    dst = out_mp4 or (os.path.join(out, "test.mp4") if test else os.path.join(ROOT, "docs", "media", "demo_score.mp4"))
    cmd = [COMPOSE_PY, "-I", os.path.join(HERE, "compose_demo.py"), out, dst, "--mic"]
    print("합치기:", " ".join(cmd), flush=True)
    subprocess.run(cmd, check=True)


def main():
    a = sys.argv[1:]
    if len(a) >= 2 and a[0] == "score" and not a[1].startswith("-"):
        opt = {}
        rest = a[2:]
        song = "03-wade"
        i = 0
        while i < len(rest):
            if rest[i] in ("--test", "--out", "--sing", "--voice") and i + 1 < len(rest):
                opt[rest[i][2:]] = rest[i + 1]
                i += 2
            elif not rest[i].startswith("-") and os.path.isdir(os.path.join(ROOT, "songs", rest[i])):
                song = rest[i]
                i += 1
            else:
                print(USAGE)
                sys.exit(2)
        t = opt.get("test")
        run_score(a[1], song, None if t is None else ("full" if t == "full" else float(t)), opt.get("out"),
                  float(opt.get("sing", 15)), opt.get("voice", "song") != "none")
        return
    if len(a) != 2 or a[0] not in ("test", "main", "lyrics") or a[1].startswith("-"):
        print(USAGE)
        sys.exit(0 if any(x in ("-h", "--help") for x in a) else 2)
    {"test": run_test, "main": run_main, "lyrics": run_lyrics}[a[0]](a[1])


if __name__ == "__main__":
    main()
