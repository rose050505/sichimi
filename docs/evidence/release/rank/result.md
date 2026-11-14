# 전국 랭킹 서버 연결 확인 (2026-11-12 13:10\~13:25)

- 빌드: `app/google-services.json`(저장소에 넣지 않음)을 넣은 release APK, SHA-256 `573e28837ca06a3f40a27f7c033bebabdf7e3b926bc38197382d5309d139e9a3`.
- 보안 규칙(호스트에서 REST로, 막혀야 할 쓰기만 시도해 문서를 남기지 않음): 남의 uid 문서, 점수 101, 앱에 없는 곡, 별명 13자, 로그인 없이 → 모두 HTTP 403. 읽기는 200(기본 "모두 막음" 규칙이 아니라 우리 규칙이 게시된 것).
- 앱 경로: 설정에서 별명 `test-delete`, 「전국 랭킹에 올리기」 켬 → `verify_g5.py full`(원곡 보컬을 가상 마이크로, 206초) → 85점, 완주, 첫 기록 → `익명 로그인 새로 함`(점수 저장 2.3초 뒤) → `랭킹에 올림: 01-arirang 85점`(4.0초 뒤). `rank_log.txt`, `full_log.txt`.
- 서버에서 다시 읽음: `rank/01-arirang/scores/{uid}` = nickname `test-delete`, score 85, at 2026-11-12T04:22:16Z, 필드는 셋뿐.
- 주차 화면 「점수」 탭: "1위 test-delete (나) 85점", "내 순위 1위 / 1명". `scores_tab.png`.
- 시험 문서(`test-delete`)는 사용자가 Firestore 콘솔에서 지운다(규칙이 앱의 지우기를 막는다). 시험 뒤 에뮬레이터 앱은 `pm clear`로 비웠다(랭킹 올리기 꺼짐).
