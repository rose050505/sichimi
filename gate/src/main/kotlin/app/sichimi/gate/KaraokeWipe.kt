package app.sichimi.gate

/** 노래방 가사 한 줄의 진행. [index]: 지금 줄(-1이면 첫 줄 전 전주). [fraction]: 그 줄이 왼쪽부터 채워진 정도(0~1). */
data class WipeLine(val index: Int, val fraction: Float)

/**
 * 노래방 기계처럼 지금 가사 줄의 글자 색을 왼쪽부터 채운다. 단어 시각이 없어서 LRC 줄 시각으로 추정한다.
 *
 * - 줄 끝 = 다음 줄 시작보다 [TAIL_GAP_MS] 앞.
 * - 다음 줄까지 간격이 길면(간주) 글자 수 × [MAX_MS_PER_CHAR]에서 자른다([MIN_LINE_MS]~[MAX_LINE_MS]).
 * - 마지막 줄은 글자 수로만 추정한다.
 */
object KaraokeWipe {
    const val TAIL_GAP_MS = 150L
    const val MAX_MS_PER_CHAR = 600L
    const val MIN_LINE_MS = 1_500L
    const val MAX_LINE_MS = 10_000L

    fun lineEndMs(starts: List<Long>, lengths: List<Int>, i: Int): Long {
        val start = starts[i]
        val cap = start + (lengths[i].coerceAtLeast(1) * MAX_MS_PER_CHAR).coerceIn(MIN_LINE_MS, MAX_LINE_MS)
        val next = starts.getOrNull(i + 1)?.let { it - TAIL_GAP_MS }
        val end = if (next != null) minOf(next, cap) else cap
        return end.coerceAtLeast(start + 1)
    }

    /** [starts]는 오름차순 줄 시작 시각, [lengths]는 줄마다 글자 수(공백 뺌). */
    fun at(starts: List<Long>, lengths: List<Int>, posMs: Long): WipeLine {
        require(starts.size == lengths.size)
        val i = lineIndex(starts, posMs)
        if (i < 0) return WipeLine(-1, 0f)
        val start = starts[i]
        val end = lineEndMs(starts, lengths, i)
        val f = ((posMs - start).toFloat() / (end - start)).coerceIn(0f, 1f)
        return WipeLine(i, f)
    }

    /** [posMs]에 부르고 있는 줄. 첫 줄 전이면 -1. */
    fun lineIndex(starts: List<Long>, posMs: Long): Int {
        var lo = 0
        var hi = starts.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** 공백을 뺀 글자 수. */
    fun letters(text: String): Int = text.count { !it.isWhitespace() }
}
