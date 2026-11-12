package app.sichimi.rank

import android.content.Context
import android.util.Log
import app.sichimi.audio.EncodedTake
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** 차 안에 둔 내 녹음 한 개(곡마다 하나, 최고 점수를 낸 판). */
data class LocalTake(
    val songId: String,
    val score: Int,
    val startMs: Long,
    val lengthMs: Long,
    val bitrate: Int,
    val cut: Boolean,
    val bytes: Int,
    val at: Long,
    /** 서버에 올린 시각. 안 올렸으면 null. */
    val uploadedAt: Long?,
)

/**
 * 내 녹음을 앱 전용 폴더(`files/takes/`)에만 둔다. 다른 앱은 읽을 수 없다.
 * 서버에는 사용자가 「점수」 탭에서 미리 들어 보고 「녹음 올리기」를 눌렀을 때만 보낸다.
 */
class TakeStore(context: Context) {
    private val dir = File(context.filesDir, "takes").apply { mkdirs() }
    private val _takes = MutableStateFlow(load())
    val takes: StateFlow<List<LocalTake>> = _takes.asStateFlow()

    private fun audio(songId: String) = File(dir, "$songId.aac")
    private fun meta(songId: String) = File(dir, "$songId.json")

    fun find(songId: String) = _takes.value.firstOrNull { it.songId == songId }

    fun bytes(songId: String): ByteArray? = audio(songId).takeIf { it.exists() }?.readBytes()

    @Synchronized
    fun save(songId: String, score: Int, e: EncodedTake) {
        audio(songId).writeBytes(e.bytes)
        write(LocalTake(songId, score, e.startMs, e.lengthMs, e.bitrate, e.cut, e.bytes.size, System.currentTimeMillis(), null))
        Log.i(TAG, "녹음 저장(차 안): $songId ${score}점 ${e.lengthMs / 1000}초 ${e.bytes.size}바이트")
    }

    @Synchronized
    fun markUploaded(songId: String, uploaded: Boolean) {
        val t = find(songId) ?: return
        write(t.copy(uploadedAt = if (uploaded) System.currentTimeMillis() else null))
    }

    @Synchronized
    fun delete(songId: String) {
        audio(songId).delete(); meta(songId).delete()
        _takes.value = load()
        Log.i(TAG, "녹음 지움(차 안): $songId")
    }

    @Synchronized
    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        _takes.value = emptyList()
    }

    private fun write(t: LocalTake) {
        meta(t.songId).writeText(JSONObject()
            .put("songId", t.songId).put("score", t.score).put("startMs", t.startMs).put("lengthMs", t.lengthMs)
            .put("bitrate", t.bitrate).put("cut", t.cut).put("bytes", t.bytes).put("at", t.at)
            .put("uploadedAt", t.uploadedAt ?: JSONObject.NULL).toString())
        _takes.value = load()
    }

    private fun load(): List<LocalTake> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching {
            val j = JSONObject(f.readText())
            val id = j.getString("songId")
            if (!audio(id).exists()) return@runCatching null
            LocalTake(id, j.getInt("score"), j.getLong("startMs"), j.getLong("lengthMs"), j.getInt("bitrate"),
                j.optBoolean("cut"), j.getInt("bytes"), j.getLong("at"), if (j.isNull("uploadedAt")) null else j.getLong("uploadedAt"))
        }.getOrNull()
    }.sortedBy { it.songId }

    companion object { const val TAG = "SC.Take" }
}
