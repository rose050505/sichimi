#!/usr/bin/env python3
"""전국 랭킹 보안 규칙(docs/firestore.rules)이 잘못된 쓰기를 막는지 REST로 확인한다.

  python3 tools/verify_rules.py emulator   # 로컬 Firestore 에뮬레이터(127.0.0.1:8085, 게시 전 확인)
  python3 tools/verify_rules.py server     # 실제 서버(규칙을 게시한 뒤). app/google-services.json을 읽는다

익명 계정 두 개(A, B)를 쓴다. B는 정상 경로(점수 문서 → 녹음)를 밟아 "맞는 쓰기는 통과"도 확인한다.
서버에서 돌리면 B의 점수 문서(별명 test-delete)가 남는다: 마지막에 경로를 찍는다(콘솔에서 지울 것).
B의 녹음 문서는 시험 끝에 B가 스스로 지운다. API 키와 프로젝트 ID는 출력하지 않는다.
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

MODE = sys.argv[1] if len(sys.argv) > 1 else "emulator"
SONG = "01-arirang"


def http(method, url, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]


if MODE == "emulator":
    PROJECT = "demo-sichimi"
    BASE = f"http://127.0.0.1:8085/v1/projects/{PROJECT}/databases/(default)/documents"

    def b64(d):
        return base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip("=")

    def account(name):
        uid = f"emu-{name}-{int(time.time())}"
        now = int(time.time())
        payload = {"sub": uid, "user_id": uid, "aud": PROJECT, "iss": f"https://securetoken.google.com/{PROJECT}",
                   "iat": now, "exp": now + 3600, "auth_time": now, "firebase": {"sign_in_provider": "anonymous", "identities": {}}}
        return uid, b64({"alg": "none", "typ": "JWT"}) + "." + b64(payload) + "."
else:
    gs = json.load(open(os.path.join(os.path.dirname(__file__), "..", "app", "google-services.json")))
    PROJECT = gs["project_info"]["project_id"]
    KEY = gs["client"][0]["api_key"][0]["current_key"]
    BASE = f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents"

    def account(name):
        req = urllib.request.Request(f"https://identitytoolkit.googleapis.com/v1/accounts:signUp?key={KEY}",
                                     data=json.dumps({"returnSecureToken": True}).encode(), method="POST")
        req.add_header("Content-Type", "application/json")
        r = json.loads(urllib.request.urlopen(req, timeout=30).read())
        return r["localId"], r["idToken"]


def now_ts():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def score_doc(nick="test-delete", score=50, extra=None):
    f = {"nickname": {"stringValue": nick}, "score": {"integerValue": str(score)}, "at": {"timestampValue": now_ts()}}
    f.update(extra or {})
    return {"fields": f}


def take_doc(size=1000, score=50, extra=None, start=4000, length=190000):
    f = {"audio": {"bytesValue": base64.b64encode(os.urandom(size)).decode()},
         "score": {"integerValue": str(score)}, "startMs": {"integerValue": str(start)},
         "lengthMs": {"integerValue": str(length)}, "at": {"timestampValue": now_ts()}}
    f.update(extra or {})
    return {"fields": f}


results = []


def check(name, expect, got):
    ok = got[0] == expect
    results.append((name, expect, got[0], ok))
    print(f"{'통과' if ok else '실패'} | {name} | 기대 {expect} | 받음 {got[0]}" + ("" if ok else f" | {str(got[1])[:120]}"))


def main():
    a_uid, a = account("a")
    b_uid, b = account("b")
    S = lambda song, uid: f"{BASE}/rank/{song}/scores/{uid}"
    T = lambda song, uid: f"{BASE}/rank/{song}/takes/{uid}"
    print(f"[{MODE}] 익명 계정 A, B 준비\n")

    print("— 점수(scores): 지난번 다섯 가지 —")
    check("1. 남의 uid 점수 문서에 쓰기", 403, http("PATCH", S(SONG, b_uid), a, score_doc()))
    check("2. 점수 101", 403, http("PATCH", S(SONG, a_uid), a, score_doc(score=101)))
    check("3. 앱에 없는 곡", 403, http("PATCH", S("99-none", a_uid), a, score_doc()))
    check("4. 별명 13자", 403, http("PATCH", S(SONG, a_uid), a, score_doc(nick="1234567890123")))
    check("5. 로그인 없이 쓰기", 403, http("PATCH", S(SONG, a_uid), None, score_doc()))
    check("읽기(공개)", 200, http("POST", f"{BASE}/rank/{SONG}:runQuery", None,
                                 {"structuredQuery": {"from": [{"collectionId": "scores"}], "limit": 1}}))

    print("\n— 녹음(takes) —")
    check("B 점수 문서 만들기(정상, 녹음의 전제)", 200, http("PATCH", S(SONG, b_uid), b, score_doc()))
    check("6. 점수 문서 없이 녹음 쓰기(A)", 403, http("PATCH", T(SONG, a_uid), a, take_doc()))
    check("7. 남의 uid 녹음 문서에 쓰기(A가 B 자리에)", 403, http("PATCH", T(SONG, b_uid), a, take_doc()))
    check("8. 녹음 점수 101", 403, http("PATCH", T(SONG, b_uid), b, take_doc(score=101)))
    check("9. 앱에 없는 곡에 녹음", 403, http("PATCH", T("99-none", b_uid), b, take_doc()))
    check("10. 정해지지 않은 필드(nickname) 더하기", 403, http("PATCH", T(SONG, b_uid), b, take_doc(extra={"nickname": {"stringValue": "x"}})))
    check("11. 필드 빠짐(startMs 없음)", 403, http("PATCH", T(SONG, b_uid), b, {"fields": {k: v for k, v in take_doc()["fields"].items() if k != "startMs"}}))
    check("12. 로그인 없이 녹음 쓰기", 403, http("PATCH", T(SONG, b_uid), None, take_doc()))
    check("13. 크기 초과(900,001바이트)", 403, http("PATCH", T(SONG, b_uid), b, take_doc(size=900_001)))
    check("14. 크기 상한 딱(900,000바이트, 정상)", 200, http("PATCH", T(SONG, b_uid), b, take_doc(size=900_000)))
    check("15. 녹음 목록 읽기(공개, 소리 빼고)", 200, http("POST", f"{BASE}/rank/{SONG}:runQuery", None,
                                                    {"structuredQuery": {"select": {"fields": [{"fieldPath": "score"}]},
                                                                         "from": [{"collectionId": "takes"}], "limit": 5}}))
    check("16. 남의 녹음 지우기(A가 B 것을)", 403, http("DELETE", T(SONG, b_uid), a))
    check("17. 로그인 없이 녹음 지우기", 403, http("DELETE", T(SONG, b_uid), None))
    check("18. 내 녹음 지우기(B, 정상)", 200, http("DELETE", T(SONG, b_uid), b))
    check("19. 점수 지우기는 본인도 막힘(B)", 403, http("DELETE", S(SONG, b_uid), b))
    gone = http("GET", T(SONG, b_uid))
    check("B 녹음이 지워졌는지(읽으면 404)", 404, gone)

    print("\n— 가장 빠른 대피(escape, v2) —")
    import datetime
    wk = datetime.date.today().isocalendar()
    WEEK = f"{wk[0]}-W{wk[1]:02d}"
    E = lambda week, song, uid: f"{BASE}/escape/{week}/songs/{song}/runs/{uid}"

    def esc(ms=420, cause="DOOR", nick="test-delete", at=None, extra=None):
        f = {"nickname": {"stringValue": nick}, "ms": {"integerValue": str(ms)}, "cause": {"stringValue": cause},
             "at": {"timestampValue": at or now_ts()}}
        f.update(extra or {})
        return {"fields": f}
    old = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 3600))
    check("E1. 정상 기록(B, 420ms)", 200, http("PATCH", E(WEEK, SONG, b_uid), b, esc()))
    check("E2. 남의 uid 자리에(A가 B 자리에)", 403, http("PATCH", E(WEEK, SONG, b_uid), a, esc(ms=300)))
    check("E3. 기록을 늘려 덮어쓰기(420 -> 900)", 403, http("PATCH", E(WEEK, SONG, b_uid), b, esc(ms=900)))
    check("E4. 더 빠른 기록으로 고치기(420 -> 350)", 200, http("PATCH", E(WEEK, SONG, b_uid), b, esc(ms=350)))
    check("E5. ms 99(너무 빠름, 조작)", 403, http("PATCH", E(WEEK, SONG, a_uid), a, esc(ms=99)))
    check("E6. ms 3001", 403, http("PATCH", E(WEEK, SONG, a_uid), a, esc(ms=3001)))
    check("E7. 원인 값이 틀림(GEAR)", 403, http("PATCH", E(WEEK, SONG, a_uid), a, esc(cause="GEAR")))
    check("E8. 주 이름 꼴이 틀림(2026-46)", 403, http("PATCH", E("2026-46", SONG, a_uid), a, esc()))
    check("E9. 앱에 없는 곡", 403, http("PATCH", E(WEEK, "99-none", a_uid), a, esc()))
    check("E10. 정해지지 않은 필드(score)", 403, http("PATCH", E(WEEK, SONG, a_uid), a, esc(extra={"score": {"integerValue": "100"}})))
    check("E11. 시각이 1시간 전", 403, http("PATCH", E(WEEK, SONG, a_uid), a, esc(at=old)))
    check("E12. 별명 13자", 403, http("PATCH", E(WEEK, SONG, a_uid), a, esc(nick="1234567890123")))
    check("E13. 로그인 없이", 403, http("PATCH", E(WEEK, SONG, a_uid), None, esc()))
    check("E14. 읽기(공개, 빠른 순)", 200, http("POST", f"{BASE}/escape/{WEEK}/songs/{SONG}:runQuery", None,
                                         {"structuredQuery": {"from": [{"collectionId": "runs"}],
                                                              "orderBy": [{"field": {"fieldPath": "ms"}, "direction": "ASCENDING"}], "limit": 5}}))
    check("E15. 남의 대피 기록 지우기(A가 B 것을)", 403, http("DELETE", E(WEEK, SONG, b_uid), a))
    check("E16. 내 대피 기록 지우기(B)", 200, http("DELETE", E(WEEK, SONG, b_uid), b))

    n_ok = sum(r[3] for r in results)
    print(f"\n결과: {n_ok}/{len(results)} 통과")
    if MODE == "server":
        print(f"지울 시험 문서(콘솔): rank/{SONG}/scores/{b_uid}  (별명 test-delete, 50점)")


if __name__ == "__main__":
    main()
