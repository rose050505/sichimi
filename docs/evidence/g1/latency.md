게스트-호스트 시계 차 보정값 82.7ms(adb 왕복 25.3ms 기준).
지연 = 콘솔에 PUT을 보낸 호스트 시각부터 앱 로그 줄의 시각까지.

| 콘솔에서 한 일 | 명령 | 지연(ms) | 앱 로그 |
|---|---|---|---|
| 동승석 착석 켜기 | `seat passenger on` | 3 | 좌석 FRONT_RIGHT callback -> Known(value=false) |
| 동승석 착석 끄기 | `seat passenger off` | -6 | 좌석 FRONT_RIGHT callback -> Known(value=true) |
| 운전석 착석 켜기 | `seat driver on` | -2 | 좌석 FRONT_LEFT callback -> Known(value=false) |
| 뒷좌석 왼쪽 착석 켜기 | `seat rear_left on` | -6 | 좌석 REAR_LEFT callback -> Known(value=false) |
| 뒷좌석 왼쪽 착석 끄기 | `seat rear_left off` | -5 | 좌석 REAR_LEFT callback -> Known(value=true) |
| 뒷좌석 오른쪽 착석 켜기 | `seat rear_right on` | -4 | 좌석 REAR_RIGHT callback -> Known(value=false) |
| 뒷좌석 오른쪽 착석 끄기 | `seat rear_right off` | -7 | 좌석 REAR_RIGHT callback -> Known(value=true) |
| 동승석 문 열기 | `door front_right open` | -6 | 문 FRONT_RIGHT callback pos=1 -> Known(value=true) |
| 동승석 문 닫기 | `door front_right close` | -2 | 문 FRONT_RIGHT callback pos=0 -> Known(value=false) |
| 운전석 문 열기 | `door front_left open` | -7 | 문 FRONT_LEFT callback pos=1 -> Known(value=true) |
| 운전석 문 닫기 | `door front_left close` | -5 | 문 FRONT_LEFT callback pos=0 -> Known(value=false) |
| 뒷문 왼쪽 열기 | `door rear_left open` | -6 | 문 REAR_LEFT callback pos=1 -> Known(value=true) |
| 뒷문 왼쪽 닫기 | `door rear_left close` | -4 | 문 REAR_LEFT callback pos=0 -> Known(value=false) |
| 뒷문 오른쪽 열기 | `door rear_right open` | -6 | 문 REAR_RIGHT callback pos=1 -> Known(value=true) |
| 뒷문 오른쪽 닫기 | `door rear_right close` | -5 | 문 REAR_RIGHT callback pos=0 -> Known(value=false) |
| 운전석 창문 30% | `window front_left 30` | -4 | 창문 FRONT_LEFT callback pos=3 -> Known(value=30) |
| 운전석 창문 2% | `window front_left 2` | -6 | 창문 FRONT_LEFT callback pos=0 -> Known(value=0) |
| 운전석 창문 3% | `window front_left 3` | 없음(3초 초과) |  |
| 운전석 창문 100% | `window front_left 100` | -5 | 창문 FRONT_LEFT callback pos=10 -> Known(value=100) |
| 운전석 창문 0% | `window front_left 0` | -4 | 창문 FRONT_LEFT callback pos=0 -> Known(value=0) |
| 뒷창문 오른쪽 50% | `window rear_right 50` | -3 | 창문 REAR_RIGHT callback pos=5 -> Known(value=50) |
| 뒷창문 오른쪽 0% | `window rear_right 0` | -6 | 창문 REAR_RIGHT callback pos=0 -> Known(value=0) |
| 기어 D | `gear D` | -7 | 기어(Pleos SDK) callback value=GEAR_DRIVE -> DRIVE |
| 기어 R | `gear R` | -1 | 기어(Pleos SDK) callback value=GEAR_REVERSE -> REVERSE |
| 기어 N | `gear N` | 7 | 기어(Pleos SDK) callback value=GEAR_NEUTRAL -> NEUTRAL |
| 기어 P | `gear P` | -4 | 기어(Pleos SDK) callback value=GEAR_PARK -> PARK |
| 방향지시등 왼쪽 | `turn left` | -3 | 방향지시등 callback value=LEFT |
| 방향지시등 끄기 | `turn off` | -5 | 방향지시등 callback value=OFF |
