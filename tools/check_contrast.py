#!/usr/bin/env python3
"""app/.../ui/Theme.kt의 색 조합이 WCAG 대비 4.5:1 이상인지 본다(Pleos 디자인 가이드: 글자·아이콘 4.5:1 이상).

  python3 tools/check_contrast.py
"""
import os
import re
import sys

SRC = os.path.join(os.path.dirname(__file__), "..", "app/src/main/java/app/sichimi/ui/Theme.kt")
pal = {k: v[-6:] for k, v in re.findall(r"val (\w+) = Color\(0x([0-9A-Fa-f]{8})\)", open(SRC, encoding="utf-8").read())}


def lum(h):
    c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    c = [x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4 for x in c]
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def ratio(a, b):
    la, lb = sorted((lum(pal[a]), lum(pal[b])), reverse=True)
    return (la + 0.05) / (lb + 0.05)


# (글자, 바탕, 최소) — 버튼 글자는 26sp 굵게(큰 글자)라 3:1이 기준이지만 여기서는 4.5를 먼저 본다
pairs = [(t, b, 4.5) for t in ("text", "sub", "dim") for b in ("bg", "surface", "surfaceHi")]
pairs += [("text", "control", 4.5), ("sub", "control", 4.5), ("text", "accentFill", 4.5), ("text", "accentPressed", 4.5),
          ("karaoke", "karaokeTint", 4.5), ("karaoke", "bg", 4.5), ("karaoke", "surface", 4.5), ("karaoke", "surfaceHi", 4.5),
          ("onKaraoke", "karaoke", 4.5), ("danger", "dangerTint", 4.5), ("danger", "surface", 4.5), ("accent", "bg", 4.5), ("accent", "surface", 4.5)]
bad = 0
for t, b, need in pairs:
    r = ratio(t, b)
    ok = r >= need
    bad += not ok
    print(f"{'통과' if ok else '부족'} {t:10s} 위 {b:12s} {r:5.2f}:1 (기준 {need})")
print("결과:", "모두 통과" if not bad else f"{bad}개 부족")
sys.exit(1 if bad else 0)
