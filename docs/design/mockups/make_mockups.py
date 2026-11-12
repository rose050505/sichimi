#!/usr/bin/env python3
"""시안 두 방향(A 한밤 무대, B 크림 그래파이트)을 정지 화면으로 그린다. 무대·점수·내 기록 3장씩.

  python3 docs/design/mockups/make_mockups.py <작업 폴더(홈 아래)>

HTML을 만들어 Firefox 헤드리스로 찍는다(스냅 Firefox는 홈 아래 파일만 읽는다). 결과는 docs/design/mockups/{A,B}_*.png.
크기는 앱이 그리는 부분(1682×1210, 1dp = 1px)만이다. 시치미 그림은 docs/media/sichimi_mascot_v2.png에서 잘라 쓴다.
데이터(별명·점수)는 시안용 예시다.
"""
import os
import subprocess
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
W, H = 1682, 1210

LOGO = open(os.path.join(HERE, "logo_mark.svgfrag")).read()


def logo(h, color="#fff", fin="#FF5FA8"):
    w = h * 304 / 110
    return f'<svg width="{w:.0f}" height="{h}" viewBox="-6 -10 304 110">' + LOGO.replace("#fff", color).replace("#FF5FA8", fin) + "</svg>"


# ---- 아이콘(24 격자, 선 2.2) ----
def ic(name, size=30, color="currentColor"):
    s = {
        "note": '<path d="M9 18.5a3 3 0 1 1-2-2.8V5l10-2v3l-8 1.6z" fill="C"/>',
        "queue": '<path d="M4 6h12M4 11h12M4 16h7" stroke="C" stroke-width="2.2" stroke-linecap="round" fill="none"/><path d="M15 14l6 3.5-6 3.5z" fill="C"/>',
        "chart": '<rect x="4" y="12.5" width="3.6" height="7.5" rx="1" fill="C"/><rect x="10.2" y="8" width="3.6" height="12" rx="1" fill="C"/><rect x="16.4" y="4" width="3.6" height="16" rx="1" fill="C"/>',
        "trophy": '<path d="M7 4h10v5a5 5 0 0 1-10 0z" fill="C"/><path d="M7 6H4v1.5A3.5 3.5 0 0 0 7.5 11M17 6h3v1.5a3.5 3.5 0 0 1-3.5 3.5" stroke="C" stroke-width="2" fill="none"/><rect x="11" y="13" width="2" height="4" fill="C"/><rect x="7.5" y="17.5" width="9" height="2.5" rx="1" fill="C"/>',
        "gear": '<circle cx="12" cy="12" r="7" stroke="C" stroke-width="3.4" stroke-dasharray="3.2 2.3" fill="none"/><circle cx="12" cy="12" r="5" stroke="C" stroke-width="2.2" fill="none"/>',
        "info": '<circle cx="12" cy="12" r="9" stroke="C" stroke-width="2.2" fill="none"/><path d="M12 11v6" stroke="C" stroke-width="2.4" stroke-linecap="round"/><circle cx="12" cy="7.6" r="1.4" fill="C"/>',
        "help": '<circle cx="12" cy="12" r="9" stroke="C" stroke-width="2.2" fill="none"/><path d="M9.3 9.4a2.8 2.8 0 1 1 3.6 2.7c-.6.2-.9.7-.9 1.3v.6" stroke="C" stroke-width="2.2" stroke-linecap="round" fill="none"/><circle cx="12" cy="17.2" r="1.3" fill="C"/>',
        "prev": '<path d="M19.5 4.5L9 12l10.5 7.5z" fill="C"/><rect x="4.5" y="4.5" width="3.1" height="15" rx="1" fill="C"/>',
        "next": '<path d="M4.5 4.5L15 12 4.5 19.5z" fill="C"/><rect x="16.4" y="4.5" width="3.1" height="15" rx="1" fill="C"/>',
        "pause": '<rect x="6" y="4.5" width="4.4" height="15" rx="1.2" fill="C"/><rect x="13.6" y="4.5" width="4.4" height="15" rx="1.2" fill="C"/>',
        "lock": '<rect x="5" y="10.5" width="14" height="10" rx="2.4" fill="C"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" stroke="C" stroke-width="2.2" fill="none"/>',
        "bolt": '<path d="M13.5 2L5 13.5h6L10 22l9-12h-6z" fill="C"/>',
        "mic": '<rect x="9" y="3" width="6" height="11" rx="3" fill="C"/><path d="M6 11a6 6 0 0 0 12 0M12 17v4" stroke="C" stroke-width="2.2" stroke-linecap="round" fill="none"/>',
        "head": '<path d="M4 15v-3a8 8 0 0 1 16 0v3" stroke="C" stroke-width="2.2" fill="none"/><rect x="3.5" y="13.5" width="4.5" height="7" rx="1.6" fill="C"/><rect x="16" y="13.5" width="4.5" height="7" rx="1.6" fill="C"/>',
        "check": '<path d="M5 12.5l5 5 9-10.5" stroke="C" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" fill="none"/>',
        "moon": '<path d="M15 3a8.5 8.5 0 1 0 6 13A7 7 0 0 1 15 3z" fill="C"/>',
        "rain": '<path d="M12 3c-4.5 4-7 7.4-7 10.3a7 7 0 0 0 14 0C19 10.4 16.5 7 12 3z" fill="C"/>',
        "ghost": '<path d="M5 20V11a7 7 0 0 1 14 0v9l-2.3-1.6L14.3 20 12 18.4 9.7 20l-2.4-1.6z" fill="C"/>',
        "star": '<path d="M12 3l2.6 5.6 6.1.7-4.5 4.2 1.2 6L12 16.6 6.6 19.5l1.2-6-4.5-4.2 6.1-.7z" fill="C"/>',
        "eye": '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z" stroke="C" stroke-width="2" fill="none"/><circle cx="12" cy="12" r="3" fill="C"/>',
        "clock": '<circle cx="12" cy="12" r="9" stroke="C" stroke-width="2.2" fill="none"/><path d="M12 7v5.5l3.5 2" stroke="C" stroke-width="2.2" stroke-linecap="round" fill="none"/>',
    }[name].replace('"C"', f'"{color}"')
    return f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" style="flex:none">{s}</svg>'


def arirang_art(variant):
    """아리랑 무대 배경(달·별·산 능선). variant A는 화면 전체, B는 띠."""
    import math
    stars = "".join(f'<circle cx="{(i * 137) % 1600 + 40}" cy="{(i * 71) % 420 + 30}" r="{1.2 + (i % 3) * 0.8}" fill="#fff" opacity="{0.25 + (i % 4) * 0.15}"/>' for i in range(46))
    ridges = ""
    for i, (c, base) in enumerate([("#3B2552", 0.60), ("#2C1B40", 0.70), ("#1C122C", 0.80)]):
        pts = [(0, H)]
        for k in range(0, 41):
            x = W * k / 40
            y = H * (base - 0.07 * math.sin((x / W) * 6.0 + i * 1.7) - 0.035 * math.sin((x / W) * 13.0 + i))
            pts.append((x, y))
        pts.append((W, H))
        ridges += '<path d="M' + " L".join(f"{x:.0f} {y:.0f}" for x, y in pts) + f' Z" fill="{c}"/>'
    moon = ('<circle cx="1320" cy="250" r="190" fill="url(#mg)"/>'
            '<path d="M1356 168 a92 92 0 1 0 50 152 a74 74 0 0 1 -50 -152 z" fill="#FFF1CF"/>')
    return (f'<svg width="{W}" height="{H}" viewBox="0 0 {W} {H}" style="position:absolute;left:0;top:0">'
            '<defs><linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#161335"/><stop offset=".55" stop-color="#2A1638"/><stop offset="1" stop-color="#140D22"/></linearGradient>'
            '<radialGradient id="mg"><stop offset="0" stop-color="#FFE3A3" stop-opacity=".35"/><stop offset="1" stop-color="#FFE3A3" stop-opacity="0"/></radialGradient></defs>'
            f'<rect width="{W}" height="{H}" fill="url(#sky)"/>{stars}{moon}{ridges}</svg>')


# ---------------- 방향 A: 한밤 무대 ----------------
A = dict(bg="#100B1C", s1="#1A1330", s2="#251C3F", line="#3A2D58", text="#FFFFFF", sub="#D2C9E3", dim="#ABA0C4",
         p="#FF5FA8", onp="#2A0716", g="#FFC65C", ong="#2A1A00", mint="#5FE3B0")
# ---------------- 방향 B: 크림 그래파이트 ----------------
B = dict(bg="#121214", s1="#1C1C20", s2="#27272C", line="#36363D", text="#FFFFFF", sub="#CACAD0", dim="#A6A6AE",
         p="#FFC864", onp="#241800", g="#FFC864", ong="#241800", mint="#59D6C2")


def css(t, kind):
    base = f"""
    *{{box-sizing:border-box}} body{{margin:0;width:{W}px;height:{H}px;overflow:hidden;background:{t['bg']};color:{t['text']};
      font-family:'NanumSquare Neo','NanumBarunGothic',sans-serif;}}
    .row{{display:flex;align-items:center}} .col{{display:flex;flex-direction:column}}
    .top{{height:108px;padding:24px 32px 0 32px;display:flex;align-items:center;gap:40px}}
    .tabs{{display:flex;gap:6px}}
    .tab{{height:72px;padding:0 26px;display:flex;align-items:center;gap:12px;font-size:26px;font-weight:700;color:{t['sub']};border-radius:36px}}
    .t24{{font-size:24px}} .t26{{font-size:26px}} .t28{{font-size:28px}} .t30{{font-size:30px}} .t32{{font-size:32px;font-weight:800}}
    .t36{{font-size:36px;font-weight:800}} .t40{{font-size:40px;font-weight:800}} .t56{{font-size:56px;font-weight:900}} .t64{{font-size:64px;font-weight:900}}
    .sub{{color:{t['sub']}}} .dim{{color:{t['dim']}}} .b{{font-weight:800}}
    """
    if kind == "A":
        base += f"""
        .tab.on{{background:{t['s2']};color:#fff;box-shadow:inset 0 0 0 2px {t['line']}}}
        .tab.on svg path,.tab.on svg rect,.tab.on svg circle{{}}
        .card{{background:{t['s1']};border-radius:28px;border:1px solid {t['line']}}}
        .hero{{background:radial-gradient(120% 140% at 20% 0%, #3A1A4A 0%, {t['s1']} 55%);border-radius:36px;border:1px solid #4A2F66}}
        .chip{{display:inline-flex;align-items:center;gap:10px;height:52px;padding:0 22px;border-radius:26px;font-size:24px;font-weight:800}}
        """
    else:
        base += f"""
        .tab.on{{background:{t['p']};color:{t['onp']}}}
        .card{{background:{t['s1']};border-radius:24px}}
        .hero{{background:{t['s1']};border-radius:24px}}
        .chip{{display:inline-flex;align-items:center;gap:10px;height:48px;padding:0 20px;border-radius:12px;font-size:24px;font-weight:800}}
        """
    return base


def topbar(t, kind, on):
    tabs = [("note", "재생·가사"), ("queue", "예약 목록"), ("chart", "내 기록"), ("trophy", "점수"), ("gear", "설정")]
    h = ""
    for i, (icn, label) in enumerate(tabs):
        sel = i == on
        col = (t["onp"] if kind == "B" else "#fff") if sel else t["dim"]
        h += f'<div class="tab {"on" if sel else ""}">{ic(icn, 28, col)}{label}</div>'
    lg = logo(44) if kind == "A" else logo(44, fin=t["p"])
    return f'<div class="top">{lg}<div class="tabs">{h}</div></div>'


def page(t, kind, body, on):
    return f"<html><head><meta charset='utf-8'><style>{css(t, kind)}</style></head><body>{topbar(t, kind, on)}{body}</body></html>"


# ================= 무대 =================
def stage_A(t):
    art = arirang_art("A")
    return page(t, "A", f"""
    <div style="position:absolute;left:32px;top:124px;width:{W-64}px;height:{H-124-32}px;border-radius:36px;overflow:hidden">
      <div style="position:absolute;left:-32px;top:-124px">{art}</div>
      <div style="position:absolute;inset:0;background:radial-gradient(60% 55% at 50% 46%, rgba(16,11,28,.70) 0%, rgba(16,11,28,.25) 70%, rgba(16,11,28,0) 100%)"></div>
      <div style="position:absolute;left:50%;top:-40px;width:900px;height:760px;transform:translateX(-50%);background:linear-gradient(180deg,rgba(255,95,168,.20),rgba(255,95,168,0));clip-path:polygon(42% 0,58% 0,100% 100%,0 100%)"></div>
      <div class="row" style="position:absolute;left:32px;top:28px;gap:20px">
        <div class="chip" style="background:rgba(255,95,168,.16);color:{t['p']};box-shadow:inset 0 0 0 2px rgba(255,95,168,.45)"><span style="width:14px;height:14px;border-radius:7px;background:{t['p']}"></span>혼자만의 노래방</div>
        <div class="t28 b">2021 아리랑 응원가</div><div class="t26 sub">윤도현</div>
      </div>
      <div style="position:absolute;right:28px;top:22px;width:64px;height:64px;border-radius:32px;background:rgba(16,11,28,.55);display:flex;align-items:center;justify-content:center">{ic('info',34,t['sub'])}</div>
      <div class="col" style="position:absolute;left:0;right:0;top:330px;align-items:center;gap:28px;text-shadow:0 4px 24px rgba(0,0,0,.6)">
        <div class="t64" style="font-size:72px"><span style="color:{t['p']}">아리랑 아리</span>랑 아라리요</div>
        <div class="t30 sub">아리랑 고개로 넘어간다</div>
      </div>
      <img src="m2.png" style="position:absolute;left:40px;bottom:150px;height:190px">
      <div style="position:absolute;left:24px;right:24px;bottom:146px;height:2px;background:linear-gradient(90deg,rgba(255,198,92,.0),rgba(255,198,92,.5) 20%,rgba(255,198,92,.0) 60%)"></div>
      <div class="row" style="position:absolute;left:50%;bottom:28px;transform:translateX(-50%);width:1080px;height:104px;border-radius:52px;background:rgba(20,13,36,.82);box-shadow:inset 0 0 0 1px {t['line']},0 10px 40px rgba(0,0,0,.45);padding:0 16px;gap:8px">
        <div style="width:96px;height:80px;display:flex;align-items:center;justify-content:center">{ic('prev',40,'#fff')}</div>
        <div style="width:88px;height:88px;border-radius:44px;background:{t['p']};display:flex;align-items:center;justify-content:center">{ic('pause',40,t['onp'])}</div>
        <div style="width:96px;height:80px;display:flex;align-items:center;justify-content:center">{ic('next',40,'#fff')}</div>
        <div class="t24 sub" style="width:84px;text-align:right;margin-left:16px">2:41</div>
        <div style="flex:1;height:8px;border-radius:4px;background:rgba(255,255,255,.18);margin:0 18px;position:relative"><div style="width:62%;height:8px;border-radius:4px;background:linear-gradient(90deg,{t['g']},{t['p']})"></div><div style="position:absolute;left:62%;top:-8px;width:24px;height:24px;margin-left:-12px;border-radius:12px;background:#fff"></div></div>
        <div class="t24 dim" style="width:84px">3:58</div>
      </div>
    </div>""", 0)


def stage_B(t):
    import math
    # 곡 아트는 왼쪽 위 포스터 띠(이중톤), 가사는 왼쪽 정렬로 크게.
    ridges = ""
    for i, (c, base) in enumerate([("#3A3426", .62), ("#2B271E", .74), ("#1E1C17", .86)]):
        pts = [(0, 400)] + [(1618 * k / 40, 400 * (base - .08 * math.sin(k / 40 * 6 + i * 1.7))) for k in range(41)] + [(1618, 400)]
        ridges += '<path d="M' + " L".join(f"{x:.0f} {y:.0f}" for x, y in pts) + f' Z" fill="{c}"/>'
    art = (f'<svg width="1618" height="400" style="position:absolute;inset:0"><rect width="1618" height="400" fill="#24211B"/>'
           '<path d="M1400 90 a62 62 0 1 0 34 102 a50 50 0 0 1 -34 -102 z" fill="#FFC864"/>' + ridges + '</svg>')
    return page(t, "B", f"""
    <div style="position:absolute;left:32px;top:124px;width:{W-64}px;height:400px;border-radius:24px;overflow:hidden">{art}
      <div class="row" style="position:absolute;left:32px;top:28px;gap:16px">
        <div class="chip" style="background:{t['p']};color:{t['onp']}">{ic('mic',26,t['onp'])}혼자만의 노래방</div>
      </div>
      <div class="col" style="position:absolute;left:32px;bottom:28px;gap:4px"><div class="t40">2021 아리랑 응원가</div><div class="t28 sub">윤도현</div></div>
      <div style="position:absolute;right:24px;top:20px;width:64px;height:64px;border-radius:16px;background:rgba(0,0,0,.35);display:flex;align-items:center;justify-content:center">{ic('info',32,'#fff')}</div>
    </div>
    <div class="col" style="position:absolute;left:64px;top:600px;gap:22px;width:1200px">
      <div class="t64" style="font-size:72px"><span style="color:{t['p']}">아리랑 아리</span>랑 아라리요</div>
      <div class="t30 sub">아리랑 고개로 넘어간다</div>
    </div>
    <img src="m2.png" style="position:absolute;right:80px;top:560px;height:200px">
    <div style="position:absolute;left:32px;right:32px;bottom:32px;height:136px;border-radius:24px;background:{t['s1']};display:flex;align-items:center;padding:0 32px;gap:20px">
      <div style="width:104px;height:88px;border-radius:16px;background:{t['s2']};display:flex;align-items:center;justify-content:center">{ic('prev',40,'#fff')}</div>
      <div style="width:168px;height:88px;border-radius:16px;background:{t['p']};display:flex;align-items:center;justify-content:center">{ic('pause',42,t['onp'])}</div>
      <div style="width:104px;height:88px;border-radius:16px;background:{t['s2']};display:flex;align-items:center;justify-content:center">{ic('next',40,'#fff')}</div>
      <div class="t24 sub" style="margin-left:24px">2:41</div>
      <div style="flex:1;height:10px;background:{t['s2']};border-radius:5px"><div style="width:62%;height:10px;background:{t['p']};border-radius:5px"></div></div>
      <div class="t24 dim">3:58</div>
    </div>""", 0)


# ================= 점수 =================
RANK = [(1, "새벽드라이버", 97), (2, "출근요정", 95), (3, "터널가왕", 94), (4, "고속도로솔로", 91), (5, "나 · 퇴근길가수", 88), (6, "빗길발라더", 86)]


def medal(rank, size, kind):
    cols = {1: ("#FFE08A", "#E8A63A", "#3A2400"), 2: ("#EEF2F8", "#9EAABC", "#1E2430"), 3: ("#F2B98F", "#B9714A", "#2E1608")}[rank]
    if kind == "B":
        return f'<div style="width:{size}px;height:{size}px;border-radius:{size//2}px;background:{cols[1]};color:{cols[2]};display:flex;align-items:center;justify-content:center;font-size:{size*0.42:.0f}px;font-weight:900">{rank}</div>'
    return (f'<div style="width:{size}px;height:{size}px;border-radius:{size//2}px;background:radial-gradient(circle at 35% 30%,{cols[0]},{cols[1]} 70%);'
            f'box-shadow:0 0 0 5px rgba(255,255,255,.08),0 6px 24px rgba(0,0,0,.4);color:{cols[2]};display:flex;align-items:center;justify-content:center;font-size:{size*0.44:.0f}px;font-weight:900">{rank}</div>')


def scores(t, kind):
    seg_on = f"background:{t['s2']};color:#fff;box-shadow:inset 0 0 0 2px {t['line']}" if kind == "A" else f"background:{t['p']};color:{t['onp']}"
    hero_num_col = t["g"]
    hero = f"""
    <div class="{'hero' if kind=='A' else 'card'} col" style="width:560px;height:{H-140}px;padding:36px;gap:18px;position:relative;overflow:hidden">
      <div class="t26 sub b">내 최고 점수</div>
      <div class="t30 b">2021 아리랑 응원가</div>
      <div class="row" style="align-items:flex-end;gap:10px;margin-top:4px"><div style="font-size:168px;line-height:150px;font-weight:900;color:{hero_num_col}">93</div><div class="t40" style="color:{hero_num_col};padding-bottom:12px">점</div></div>
      <div class="chip" style="align-self:flex-start;background:rgba(95,227,176,.14);color:{t['mint']}">▲ 지난 최고보다 8점</div>
      <div style="height:1px;background:{t['line']};margin:18px 0 6px"></div>
      <div class="t24 dim b">곡별 최고</div>
      {''.join(f'<div class="row" style="height:72px"><div class="t28 b" style="flex:1">{n}</div><div class="t32" style="color:{t["g"] if i==0 else "#fff"}">{s}점</div></div>' for i,(n,s) in enumerate([("2021 아리랑 응원가",93),("I Want To Destroy…",81),("Wade","—")]))}
      <img src="m3.png" style="position:absolute;right:20px;bottom:20px;height:170px;opacity:.95">
    </div>"""
    podium_cols = ""
    for r, h in ((2, 150), (1, 200), (3, 116)):
        n, s = RANK[r - 1][1], RANK[r - 1][2]
        base = (f"background:linear-gradient(180deg,{t['s2']},{t['s1']});border-radius:24px 24px 0 0;box-shadow:inset 0 2px 0 {t['line']}" if kind == "A"
                else f"background:{t['s2']};border-radius:16px 16px 0 0")
        podium_cols += f"""<div class="col" style="flex:1;align-items:center;justify-content:flex-end;gap:12px">
            {medal(r, 104 if r==1 else 84, kind)}<div class="t28 b">{n}</div><div class="t36" style="color:{t['g'] if r==1 else '#fff'}">{s}점</div>
            <div style="width:100%;height:{h}px;{base};display:flex;justify-content:center;padding-top:14px" class="t24 dim b">{r}위</div></div>"""
    rows = ""
    for r, n, s in RANK[3:]:
        me = "나" in n
        st = (f"background:rgba(255,95,168,.12);box-shadow:inset 0 0 0 2px {t['p']}" if kind == "A" else f"background:rgba(255,200,100,.12);box-shadow:inset 0 0 0 2px {t['p']}") if me else ""
        rows += f"""<div class="row" style="height:88px;padding:0 28px;border-radius:20px;{st}">
            <div class="t30 b {'' if me else 'dim'}" style="width:72px">{r}</div><div class="t28 b" style="flex:1">{n}</div>
            {'<div class="chip" style="height:48px;background:'+t['s2']+';color:#fff;margin-right:20px">'+ic('head',24,'#fff')+'듣기 3:41</div>' if not me else ''}
            <div class="t32">{s}점</div></div>"""
    body = f"""
    <div class="row" style="position:absolute;left:32px;top:124px;right:32px;bottom:16px;gap:28px;align-items:flex-start">
      {hero}
      <div class="col" style="flex:1;gap:20px">
        <div class="row" style="gap:20px"><div class="t32" style="flex:1">전국 랭킹</div>
          <div class="row" style="background:{t['s1']};border-radius:{'36' if kind=='A' else '16'}px;padding:6px;gap:6px">
            <div class="tab" style="height:64px;{seg_on}">{ic('trophy',26,'#fff' if kind=='A' else t['onp'])}점수</div><div class="tab" style="height:64px">{ic('bolt',26,t['dim'])}가장 빠른 대피</div></div></div>
        <div class="row" style="gap:12px">{''.join(f'<div class="chip" style="height:56px;{"background:"+t["s2"]+";color:#fff" if i==0 else "background:transparent;color:"+t["sub"]+";box-shadow:inset 0 0 0 2px "+t["line"]}">{x}</div>' for i,x in enumerate(["2021 아리랑 응원가","I Want To Destroy Something Beautiful","Wade"]))}</div>
        <div class="row" style="gap:20px;align-items:flex-end;height:440px;padding:0 20px">{podium_cols}</div>
        <div class="col" style="gap:6px">{rows}</div>
        <div class="t24 sub" style="padding-left:28px">내 순위 5위 / 128명 · 이 곡을 끝까지 부르면 다시 올라갑니다</div>
      </div>
    </div>"""
    return page(t, kind, body, 3)


# ================= 내 기록 =================
BADGES = [("첫 무대", True, "star"), ("0.3초 대피의 달인", True, "bolt"), ("시치미 3단", False, "eye"), ("한 번도 안 들킨 주", False, "check"),
          ("빗속의 가수", False, "rain"), ("밤의 가수", True, "moon"), ("출근길 10곡", False, "note"), ("고스트 버스터", False, "ghost")]


def badge(name, on, icn, t, kind):
    if on:
        disc = (f"background:radial-gradient(circle at 35% 30%,#FFE6A6,{t['g']} 60%,#D98A2B);box-shadow:0 0 0 6px rgba(255,198,92,.16),0 8px 28px rgba(255,150,60,.25)" if kind == "A"
                else f"background:{t['p']}")
        return f'<div class="col" style="align-items:center;gap:12px;width:150px"><div style="width:104px;height:104px;border-radius:52px;{disc};display:flex;align-items:center;justify-content:center">{ic(icn,50,t["ong"])}</div><div class="t24 b" style="text-align:center">{name}</div></div>'
    return f'<div class="col" style="align-items:center;gap:12px;width:150px"><div style="width:104px;height:104px;border-radius:52px;border:3px dashed {t["line"]};display:flex;align-items:center;justify-content:center;opacity:.9">{ic(icn,46,t["line"])}</div><div class="t24 dim" style="text-align:center">{name}</div></div>'


def records(t, kind):
    stage_nodes = "".join(f'<div style="position:absolute;left:{p}%;top:-9px;width:34px;height:34px;margin-left:-17px;border-radius:17px;background:{"#fff" if i<2 else t["s2"]};color:{t["bg"] if i<2 else t["dim"]};font-size:20px;font-weight:900;display:flex;align-items:center;justify-content:center;box-shadow:0 0 0 4px {t["s1"]}">{i+1}</div>' for i, p in enumerate([0, 13.3, 40, 100]))
    bar = f"linear-gradient(90deg,{t['g']},{t['p']})" if kind == "A" else t["p"]
    hero = f"""
    <div class="hero row" style="height:360px;padding:36px 40px;gap:40px;position:relative">
      <div style="width:300px;height:290px;display:flex;align-items:flex-end;justify-content:center;{'background:radial-gradient(50% 50% at 50% 70%,rgba(255,95,168,.22),rgba(0,0,0,0))' if kind=='A' else ''}"><img src="m2.png" style="height:220px"></div>
      <div class="col" style="flex:1;gap:14px">
        <div class="row" style="gap:16px"><div class="t36">시치미 2단계 · 콧노래</div><div class="chip" style="background:rgba(255,198,92,.14);color:{t['g']}">칭호 「첫 무대」</div></div>
        <div class="row" style="align-items:flex-end;gap:16px;margin-top:6px"><div class="t26 sub" style="padding-bottom:12px">다음 단계 「골목 가수」까지</div><div style="font-size:88px;line-height:88px;font-weight:900;color:{t['g']}">29점</div></div>
        <div class="t26 sub">혼자 29분 부르거나 완창 6곡이면 돼요</div>
        <div style="position:relative;height:16px;border-radius:8px;background:{t['s2']};margin:26px 17px 0 17px"><div style="width:20%;height:16px;border-radius:8px;background:{bar}"></div>{stage_nodes}</div>
      </div>
      <div style="position:absolute;right:28px;top:28px;width:64px;height:64px;border-radius:32px;background:{t['s2']};display:flex;align-items:center;justify-content:center">{ic('help',34,t['sub'])}</div>
    </div>"""
    stamps = "".join(f'<div style="width:76px;height:76px;border-radius:{"38" if kind=="A" else "16"}px;{"background:"+t["p"]+";color:"+t["onp"] if i<2 else "border:3px dashed "+t["line"]+";color:"+t["dim"]};display:flex;align-items:center;justify-content:center;font-size:28px;font-weight:900">{"✓" if i<2 else i+1}</div>' for i in range(5))
    missions = "".join(f'<div class="col" style="gap:10px"><div class="row"><div class="t28 b" style="flex:1">{n}</div><div class="t24 {"" if d else "dim"}" style="color:{t["mint"] if d else ""}">{p}</div></div><div style="height:10px;border-radius:5px;background:{t["s2"]}"><div style="width:{f}%;height:10px;border-radius:5px;background:{t["mint"] if d else t["sub"]}"></div></div></div>'
                       for n, p, f, d in [("80점 넘기기", "완료 +15", 100, True), ("한 번도 안 들키고 3곡", "1 / 3곡", 33, False), ("비 오는 날 1곡", "0 / 1곡", 2, False)])
    row2 = f"""
    <div class="row" style="gap:24px;margin-top:24px;align-items:stretch">
      <div class="card col" style="width:620px;padding:32px;gap:24px"><div class="row"><div class="t30 b" style="flex:1">이번 주 출근길 투어</div><div class="t26 sub">공연 2 / 5</div></div><div class="row" style="gap:20px">{stamps}</div></div>
      <div class="card col" style="flex:1;padding:32px;gap:22px"><div class="t30 b">이번 주 미션</div>{missions}</div>
    </div>"""
    row3 = f"""
    <div class="card col" style="margin-top:24px;padding:32px 32px 28px;gap:24px;{'background:transparent;border:1px solid '+t['line'] if kind=='A' else ''}">
      <div class="row"><div class="t30 b" style="flex:1">칭호와 배지</div><div class="t26 sub">3 / 8</div></div>
      <div class="row" style="justify-content:space-between">{''.join(badge(n,o,i,t,kind) for n,o,i in BADGES)}</div>
    </div>"""
    body = f'<div class="col" style="position:absolute;left:32px;right:32px;top:124px">{hero}{row2}{row3}</div>'
    return page(t, kind, body, 2)


def render(work):
    os.makedirs(work, exist_ok=True)
    for f in ("m2.png", "m3.png"):
        src = os.path.join(HERE, "assets", f)
        Image.open(src).save(os.path.join(work, f))
    prof = os.path.join(work, "prof")
    os.makedirs(prof, exist_ok=True)
    pages = {"A_1_stage": stage_A(A), "A_2_scores": scores(A, "A"), "A_3_records": records(A, "A"),
             "B_1_stage": stage_B(B), "B_2_scores": scores(B, "B"), "B_3_records": records(B, "B")}
    for name, html in pages.items():
        hp = os.path.join(work, name + ".html")
        open(hp, "w").write(html)
        out = os.path.join(work, name + ".png")
        subprocess.run(["timeout", "90", "firefox", "--headless", "--no-remote", "--profile", prof, "--screenshot", out,
                        f"--window-size={W},{H}", "file://" + hp], capture_output=True)
        Image.open(out).convert("RGB").save(os.path.join(HERE, name + ".png"))
        print("그림", name)


if __name__ == "__main__":
    render(sys.argv[1])
