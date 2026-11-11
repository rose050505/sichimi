#!/usr/bin/env python3
"""장면 6(설계 8절, 2순위): 노래방 중 기어 R이나 방향지시등이면 가수가 잠시 돌아오고, 끝나면 1.5초 뒤 다시 물러난다.

콘솔 조작은 콘솔 페이지와 같은 HTTP 경로로 한다. 앱 안의 가짜 입력은 쓰지 않는다.

  tools/verify_scene6.py docs/evidence/scene6
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import verify_scenes as vs  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/evidence/scene6"
vs.OUT = OUT


def texts_with(word, tries=4):
    """uiautomator 덤프가 화면이 바뀌는 중에는 비어 올 때가 있어 몇 번 다시 읽는다."""
    texts = []
    for _ in range(tries):
        texts = vs.screen_texts()
        if word in texts:
            break
        time.sleep(0.3)
    return texts


def main():
    os.makedirs(OUT, exist_ok=True)
    rows = []

    def row(scene, did, expect, ok, note=""):
        rows.append((scene, did, expect, "통과" if ok else "**실패**", note))
        print(scene, did, "->", "통과" if ok else "실패", note)

    vs.sh("adb", "shell", "pm", "clear", "--user", "10", vs.PKG, check=False)
    for seat, v in (("driver", "on"), ("passenger", "off"), ("rear_left", "off"), ("rear_right", "off")):
        vs.c("seat", seat, v)
    for z in ("front_left", "front_right", "rear_left", "rear_right"):
        vs.c("door", z, "close"); vs.c("window", z, "0")
    vs.c("turn", "off"); vs.c("gear", "D")
    vs.sh("adb", "logcat", "-c")
    vs.sh("adb", "shell", "am", "start", "--user", "10", "-n", f"{vs.PKG}/.MainActivity")
    time.sleep(4)
    if not vs.tap_text("재생"):
        vs.sh("adb", "shell", "input", "tap", "1693", "812")
    t = vs.wait_state("KARAOKE", 12)
    row("6-0", "운전석만 착석, 기어 D에서 재생", "노래방", t is not None, f"재생 후 {t:.1f}초" if t else "")

    # 기어 R
    vs.c("gear", "R")
    t = vs.wait_state("RETURN", 2)
    time.sleep(0.4)
    vs.shot("s6a_reverse")
    texts = texts_with("잠시 가수 복귀")
    seen = "화면 글자 읽힘" if "잠시 가수 복귀" in texts else "화면 글자는 스크린샷으로 확인(시스템 카메라 창이 위에 떠 덤프에 안 잡힘)"
    row("6a", "기어 R", "곧바로 잠시 복귀, '잠시 가수 복귀'", t is not None,
        (f"{t * 1000:.0f}ms 안에 전이, " if t is not None else "") + seen)
    time.sleep(2.0)
    row("6b", "R 유지 2초", "잠시 복귀 유지", vs.last_state() == "RETURN", vs.last_state())
    vs.c("gear", "D")
    t_d = time.time()
    t = vs.wait_state("KARAOKE", 5)
    waited = (time.time() - t_d) if t is not None else None
    vs.shot("s6c_drive_again")
    row("6c", "다시 기어 D", "1.5초 뒤 다시 노래방", waited is not None and 1.3 <= waited <= 2.5,
        f"D 뒤 {waited:.1f}초" if waited else "")

    # 방향지시등
    vs.c("turn", "left")
    t = vs.wait_state("RETURN", 2)
    time.sleep(1.5)  # 시스템 측면 카메라 창이 뜬 뒤를 담는다
    vs.shot("s6d_turn_signal")
    texts = texts_with("잠시 가수 복귀")
    seen = "화면 글자 읽힘" if "잠시 가수 복귀" in texts else "화면 글자는 스크린샷으로 확인(시스템 카메라 창이 위에 떠 덤프에 안 잡힘)"
    row("6d", "방향지시등 왼쪽", "곧바로 잠시 복귀", t is not None,
        (f"{t * 1000:.0f}ms 안에 전이, " if t is not None else "") + seen)
    vs.c("turn", "off")
    t_off = time.time()
    t = vs.wait_state("KARAOKE", 5)
    waited = (time.time() - t_off) if t is not None else None
    row("6e", "방향지시등 끄기", "1.5초 뒤 다시 노래방", waited is not None and 1.3 <= waited <= 2.5,
        f"끈 뒤 {waited:.1f}초" if waited else "")

    # 잠시 복귀 중 문이 열리면 들킬 뻔(대피 시간은 없음)으로 남는다
    vs.c("gear", "R")
    vs.wait_state("RETURN", 2)
    vs.c("door", "front_right", "open")
    t = vs.wait_state("NORMAL", 2)
    row("6f", "후진 중 동승석 문 열기", "일반으로(들킬 뻔 1번, 대피 시간 없음)", t is not None,
        f"{t * 1000:.0f}ms 안에 전이" if t is not None else "")
    vs.c("door", "front_right", "close")
    time.sleep(1)
    vs.c("gear", "D")
    vs.wait_state("KARAOKE", 12)
    time.sleep(3)
    vs.c("gear", "P")
    time.sleep(3)
    vs.shot("s6g_parked_summary")
    texts = vs.screen_texts()
    summary = next((x for x in texts if x.startswith("오늘 ")), "")
    row("6g", "기어 P", "요약의 들킬 뻔은 문 1번뿐(후진·방향지시등은 세지 않음)", "들킬 뻔한 순간 1번" in summary, summary)

    with open(os.path.join(OUT, "gate_log.txt"), "w") as f:
        f.write(vs.gate_log())
    md = ["| 장면 | 콘솔·앱에서 한 일 | 기대 | 결과 | 비고 |", "|---|---|---|---|---|"]
    md += [f"| {a} | {b} | {e} | {r} | {n} |" for a, b, e, r, n in rows]
    with open(os.path.join(OUT, "result.md"), "w") as f:
        f.write("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
