package app.sichimi.gate

/**
 * 박자 세기(카운트인). 첫 소절 바로 앞 네 박에 드럼 스틱 소리(탁, 탁, 탁, 탁)를 내서, 가수 목소리가 물러난 반주만 듣고도
 * 들어갈 때를 잡게 한다. 박자 소리는 곡 파일에 들어 있고(tools/count_in.py), 여기서는 들려줄지만 정한다.
 *
 * - 혼자만의 노래방([GateState.KARAOKE])일 때만 들려준다. 가수 목소리가 그대로일 때는 필요 없고,
 *   동승자가 있을 때 박자 소리가 나면 노래방이 들킨다(설계 1절 원칙 2).
 * - 설정에서 끌 수 있다. 기본은 켬.
 */
object CountIn {
    fun audible(state: GateState, enabled: Boolean): Boolean = enabled && state == GateState.KARAOKE
}
