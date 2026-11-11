package app.sichimi.songs

import android.content.Context
import android.net.Uri
import org.json.JSONObject

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri,
    val lyricsAsset: String?,
    val source: String,
    val license: String,
)

/**
 * 곡 목록. 지금은 앱에 든 곡만 쓴다.
 * 나중에 반주 음원 사업자의 목록을 붙일 자리다(설계 4절).
 */
interface SongSource {
    fun songs(): List<Song>
    fun find(id: String): Song? = songs().firstOrNull { it.id == id }
    fun lyrics(song: Song): List<LyricLine>
}

/** `assets/<id>/{mix4.flac|mix2.flac, meta.json, lyrics.lrc}`를 읽는다. 저장소의 `songs/` 폴더가 assets로 들어간다. */
class AssetSongSource(private val context: Context) : SongSource {
    private val cache: List<Song> by lazy { load() }

    override fun songs(): List<Song> = cache

    private fun load(): List<Song> {
        val am = context.assets
        val dirs = am.list("")?.sorted().orEmpty()
        return dirs.mapNotNull { dir ->
            val files = am.list(dir)?.toSet().orEmpty()
            if ("meta.json" !in files) return@mapNotNull null
            val audio = when {
                "mix4.flac" in files -> "mix4.flac"
                "mix2.flac" in files -> "mix2.flac"
                else -> return@mapNotNull null
            }
            val meta = JSONObject(am.open("$dir/meta.json").bufferedReader().use { it.readText() })
            Song(
                id = dir,
                title = meta.optString("title", dir),
                artist = meta.optString("artist", ""),
                durationMs = meta.optLong("durationMs", 0),
                uri = Uri.parse("asset:///$dir/$audio"),
                lyricsAsset = if ("lyrics.lrc" in files) "$dir/lyrics.lrc" else null,
                source = meta.optString("source", ""),
                license = meta.optString("license", ""),
            )
        }
    }

    override fun lyrics(song: Song): List<LyricLine> {
        val path = song.lyricsAsset ?: return emptyList()
        val text = context.assets.open(path).bufferedReader().use { it.readText() }
        return LyricLine.parseLrc(text)
    }
}

data class LyricLine(val atMs: Long, val text: String) {
    companion object {
        private val TAG = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")

        fun parseLrc(text: String): List<LyricLine> = text.lineSequence().flatMap { line ->
            val tags = TAG.findAll(line).toList()
            if (tags.isEmpty()) return@flatMap emptySequence()
            val body = line.substring(tags.last().range.last + 1).trim()
            tags.asSequence().map { m ->
                val ms = m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong()
                LyricLine(ms, body)
            }
        }.sortedBy { it.atMs }.toList()
    }
}
