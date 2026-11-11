# NOTICE

시치미(Sichimi)에 들어 있거나 만들 때 쓴 음원과 라이브러리의 출처와 라이선스입니다.

## 음원

앱에 든 곡은 모두 원곡을 [Demucs](https://github.com/facebookresearch/demucs)(htdemucs)로 **보컬과 반주 두 갈래로 나누고**, 4채널 Opus 한 파일(`songs/<id>/mix4.ogg`)로 묶은 것입니다. 원곡을 바꾼 것이므로 아래에 출처와 함께 적습니다. 가사 파일(`lyrics.lrc`)은 나눈 보컬을 자동 받아쓰기(faster-whisper)한 뒤 다듬은 것이라 원래 가사와 다를 수 있습니다.

| 폴더 | 곡 | 저작자 | 출처 | 라이선스 | 바꾼 것 |
|---|---|---|---|---|---|
| `songs/01-arirang` | 2021 아리랑 응원가 (윤도현 가창 음원) | 기증: 윤도현, 성윤용, 김민기, 강규용, 김승남, 박상우, 안도형, 윤정훈 | [공유마당(한국저작권위원회)](https://gongu.copyright.or.kr/gongu/wrt/wrt/view.do?wrtSn=13287221&menuNo=200020), 파일은 [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:2021_%EC%95%84%EB%A6%AC%EB%9E%91_%EC%9D%91%EC%9B%90%EA%B0%80_%EC%9C%A4%EB%8F%84%ED%98%84_%EA%B0%80%EC%B0%BD_%EC%9D%8C%EC%9B%90.flac) | 공유마당 기증저작물 자유이용. 공유마당의 표시 방법에 따라 "2021 아리랑 응원가 윤도현 가창 음원, 기증 윤도현 외 7인, 공유마당, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ko)"으로 표시합니다(Commons 표기도 CC BY 4.0) | 보컬·반주 분리, Opus 변환, 가사 받아쓰기 |
| `songs/02-destroy` | I Want To Destroy Something Beautiful (앨범 Dirty Wings) | Josh Woodward | [joshwoodward.com](https://www.joshwoodward.com/song/IWantToDestroySomethingBeautiful) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | 보컬·반주 분리, Opus 변환, 가사 받아쓰기 |
| `songs/03-wade` | Wade | Josh Woodward | [joshwoodward.com](https://www.joshwoodward.com/song/Wade) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | 보컬·반주 분리, Opus 변환, 가사 받아쓰기 |


## 앱에 들어간 라이브러리

| 라이브러리 | 버전 | 라이선스 |
|---|---|---|
| Pleos Playground Vehicle SDK (`ai.pleos.playground:Vehicle`) | 2.3.2 | Pleos Playground 제공 SDK. 배포 POM에 라이선스 표기가 없음. 현대자동차그룹 차량용 앱 개발 공모전 개발용으로 제공됨 |
| Pleos Playground NaviHelper SDK (`ai.pleos.playground:NaviHelper`) | 3.0.5 | 위와 같음 |
| Pleos Playground TextToSpeech SDK (`ai.pleos.playground:TextToSpeech`) | 3.3.1 | 위와 같음(함께 들어오는 `ai.pleos.playground.security:universal-access` 포함) |
| Android Car API (`android.car`, 플랫폼 라이브러리) | API 34 | Apache License 2.0 |
| AndroidX Core KTX, Activity Compose, Lifecycle | 1.13.1, 1.9.3, 2.8.7 | Apache License 2.0 |
| Jetpack Compose (BOM 2024.10.01, Material 3) | — | Apache License 2.0 |
| AndroidX Media3 ExoPlayer, Session | 1.4.1 | Apache License 2.0 |
| AndroidX Room | 2.6.1 | Apache License 2.0 |
| Kotlin 표준 라이브러리, kotlinx.coroutines | 2.0.21, 1.8.1 | Apache License 2.0 |
| Gson (TextToSpeech SDK가 씀) | 2.10.1 | Apache License 2.0 |

## 만들 때만 쓴 도구 (앱에 들어가지 않음)

| 도구 | 쓰는 곳 | 라이선스 |
|---|---|---|
| Demucs (htdemucs 모델) | 곡을 보컬과 반주로 나눔 | MIT License |
| faster-whisper, Whisper 모델 | 나눈 보컬에서 가사 초안 받아쓰기 | MIT License |
| PyTorch | Demucs 실행 | BSD-3-Clause |
| FFmpeg | 스템을 4채널 한 파일로 묶음, 녹음 변환 | LGPL 2.1 이상(빌드에 따라 GPL) |
| JUnit 4 | 문지기 단위 테스트 | Eclipse Public License 1.0 |
