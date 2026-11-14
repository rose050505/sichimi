# 랭킹 다듬기 확인 (2026-11-12 14:55\~15:15)

빌드: release APK, SHA-256 `226c71900b19b8571b15740007d38db96d91663d4aa07adfcf262864516a6e6e`. 단위 테스트 77개 통과.

## A. 보기만 하면 익명 계정을 만들지 않는다
- `pm clear` 직후(올린 적 없음) 주차 화면 「점수」 탭을 열었다 → "아직 이 곡의 기록이 없습니다"(서버에서 읽기 성공, 실패 문구 아님). `a_read_no_account.png`
- 그동안 `SC.Rank` 로그에 "익명 로그인 새로 함"이 없다. `a_rank_log.txt`
- 같은 때 호스트에서 로그인 없이 읽은 서버 결과도 0건, HTTP 200.

## B. 별명 글자를 눌러도 바로 고칠 수 있다
- 설정 「랭킹 별명」의 별명 글자(바꾸기 버튼이 아님)를 눌렀다 → 편집 칸 focused=true, 키보드가 바로 뜸. 칸을 다시 누르지 않고 `test-delete`를 입력하고 저장. `b_nick_timeline.txt`, `b_nick_editing.png`, `b_nick_saved.png`
- 녹화(12MB)는 저장소 밖(개발 PC)에 두었다.

## C. 못 올린 최고 점수를 다음에 켤 때 올린다
- 「전국 랭킹에 올리기」 켬, 인터넷만 끊음(개인 DNS를 없는 주소로: 차 신호는 그대로). `verify_g5.py full`로 아리랑 완주 84점 → "랭킹 올리기 실패: UnknownHostException". `c1_offline_log.txt`, `c_offline/full_log.txt`
- 인터넷을 되살리고 앱을 다시 켬 → 2초 뒤 "익명 로그인 새로 함", 4초 뒤 "랭킹에 올림: 01-arirang 84점". `c2_restart_log.txt`
- 서버: `rank/01-arirang/scores/{uid}` = test-delete, 84, 필드 셋. `c3_server.txt`
- 한 번 더 다시 켬 → 서버 점수와 같아 쓰지 않음(로그 없음). `c4_second_restart_log.txt`
- 점수 탭: "1위 test-delete (나) 84점", "내 순위 1위 / 1명". `c5_scores_tab.png`

시험 문서(`test-delete`)는 사용자가 Firestore 콘솔에서 지운다. 시험 뒤 에뮬레이터 앱은 `pm clear`로 비웠다.
