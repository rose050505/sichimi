package app.sichimi.rank

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.sichimi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** 서버에 올라간 녹음의 요약(소리는 빼고). [startMs]: 녹음의 첫 표본이 곡의 몇 ms인가. */
data class TakeMeta(val score: Int, val startMs: Long, val lengthMs: Long)

/** 전국 랭킹 한 줄. [take]: 이 사람이 올린 녹음(없으면 null). */
data class RankEntry(val rank: Int, val nickname: String, val score: Int, val mine: Boolean,
                     val uid: String = "", val take: TakeMeta? = null)

/** 한 곡의 랭킹: 위 몇 명과 내 순위(올린 적이 없으면 null). */
data class SongRanking(val top: List<RankEntry>, val myRank: Int?, val myScore: Int?, val total: Int)

/**
 * 전국 랭킹(Firebase Firestore). Pleos 차에는 Google Play 서비스가 없어서 SDK 대신 REST로 부른다.
 *
 * - 익명 로그인(Identity Toolkit `accounts:signUp`)으로 이 차의 uid를 받는다. 이름·연락처는 받지 않는다.
 * - 저장 위치: `rank/{곡 id}/scores/{uid}` = {nickname, score, at}. 한 사람이 한 곡에 한 줄(최고 점수만).
 * - 보내는 것: 사용자가 정한 별명, 곡 id, 점수, 시각. 차 신호, 주행 기록은 보내지 않는다.
 * - 녹음: 사용자가 「녹음 올리기」를 눌렀을 때만 `rank/{곡 id}/takes/{uid}` = {audio(AAC 바이트), score, startMs, lengthMs, at}.
 *   점수 문서와 따로 두어 랭킹 목록을 읽을 때 소리까지 받지 않는다. 지우기는 본인만(규칙).
 * - 보안 규칙(서버): 자기 uid 문서만 쓸 수 있고, 점수는 0~100 정수, 별명은 1~12자, 녹음은 900,000바이트 이하(docs/firestore.rules).
 */
class RankingClient(private val context: Context) {
    private val project = BuildConfig.RANK_PROJECT
    private val key = BuildConfig.RANK_API_KEY
    private val prefs = context.getSharedPreferences("rank", Context.MODE_PRIVATE)
    private val authLock = Mutex()

    val configured: Boolean get() = project.isNotEmpty() && key.isNotEmpty()

    private var idToken: String? = null
    private var tokenUntil = 0L

    private val base get() = "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents"

    /** 이 곡의 내 점수를 올린다. 서버에 있는 내 점수보다 높을 때만 바꾼다. */
    suspend fun submit(songId: String, nickname: String, score: Int): Boolean = io {
        val (uid, token) = auth()
        val doc = "$base/rank/${enc(songId)}/scores/$uid"
        val existing = runCatching { request("GET", doc, token, null) }.getOrNull()
        val old = existing?.optJSONObject("fields")?.optJSONObject("score")?.optString("integerValue")?.toIntOrNull()
        if (old != null && old >= score) {
            // 별명만 바뀌었으면 별명은 고친다
            val oldName = existing.optJSONObject("fields")?.optJSONObject("nickname")?.optString("stringValue")
            if (oldName == nickname) return@io true
        }
        val body = JSONObject().put("fields", JSONObject()
            .put("nickname", JSONObject().put("stringValue", nickname))
            .put("score", JSONObject().put("integerValue", maxOf(score, old ?: 0).toString()))
            .put("at", JSONObject().put("timestampValue", java.time.Instant.now().toString())))
        request("PATCH", "$doc?updateMask.fieldPaths=nickname&updateMask.fieldPaths=score&updateMask.fieldPaths=at", token, body)
        Log.i(TAG, "랭킹에 올림: $songId ${maxOf(score, old ?: 0)}점")
        true
    }

    /** 한 곡의 랭킹을 읽는다(위 [limit]명 + 내 순위). */
    suspend fun ranking(songId: String, limit: Int = 10): SongRanking = io {
        // 읽기는 공개다. 랭킹을 보기만 하는 사람은 익명 계정도 만들지 않는다(올린 적이 있을 때만 내 uid로 "나"를 표시).
        val uid = prefs.getString("uid", null)
        val token = if (uid != null) runCatching { auth().second }.getOrNull() else null
        val query = JSONObject().put("structuredQuery", JSONObject()
            .put("from", JSONArray().put(JSONObject().put("collectionId", "scores")))
            .put("orderBy", JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", "score")).put("direction", "DESCENDING")))
            .put("limit", 200))
        val arr = requestArray("POST", "$base/rank/${enc(songId)}:runQuery", token, query)
        // 녹음 목록: 소리(audio)는 빼고 요약 필드만 받는다. 실패해도 랭킹은 보인다.
        val takes = runCatching { takeIndex(songId, token) }.onFailure { Log.w(TAG, "녹음 목록 읽기 실패: $it") }.getOrDefault(emptyMap())
        val rows = (0 until arr.length()).mapNotNull { i ->
            val d = arr.getJSONObject(i).optJSONObject("document") ?: return@mapNotNull null
            val f = d.getJSONObject("fields")
            Triple(d.getString("name").substringAfterLast('/'), f.optJSONObject("nickname")?.optString("stringValue") ?: "?",
                f.optJSONObject("score")?.optString("integerValue")?.toIntOrNull() ?: 0)
        }
        // 같은 점수는 같은 순위
        val ranked = rows.map { (id, name, score) ->
            RankEntry(rows.indexOfFirst { it.third == score } + 1, name, score, id == uid, id, takes[id])
        }
        val me = ranked.firstOrNull { it.mine }
        SongRanking(ranked.take(limit), me?.rank, me?.score, rows.size)
    }

    // ---------- 녹음 ----------

    private fun takeIndex(songId: String, token: String?): Map<String, TakeMeta> {
        val fields = JSONArray().apply { listOf("score", "startMs", "lengthMs").forEach { put(JSONObject().put("fieldPath", it)) } }
        val query = JSONObject().put("structuredQuery", JSONObject()
            .put("select", JSONObject().put("fields", fields))
            .put("from", JSONArray().put(JSONObject().put("collectionId", "takes")))
            .put("limit", 200))
        val arr = requestArray("POST", "$base/rank/${enc(songId)}:runQuery", token, query)
        return (0 until arr.length()).mapNotNull { i ->
            val d = arr.getJSONObject(i).optJSONObject("document") ?: return@mapNotNull null
            val f = d.getJSONObject("fields")
            d.getString("name").substringAfterLast('/') to TakeMeta(int(f, "score").toInt(), int(f, "startMs"), int(f, "lengthMs"))
        }.toMap()
    }

    private fun int(f: JSONObject, k: String) = f.optJSONObject(k)?.optString("integerValue")?.toLongOrNull() ?: 0L

    /**
     * 내 녹음을 올린다. 점수 문서가 있어야 규칙이 받아 주므로 점수를 먼저 올린다(같으면 쓰지 않음).
     * 녹음이 실패해도 점수는 이미 올라가 있다.
     */
    suspend fun submitTake(songId: String, nickname: String, take: LocalTake, audio: ByteArray) = io {
        submit(songId, nickname, take.score)
        val (uid, token) = auth()
        val body = JSONObject().put("fields", JSONObject()
            .put("audio", JSONObject().put("bytesValue", android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)))
            .put("score", JSONObject().put("integerValue", take.score.toString()))
            .put("startMs", JSONObject().put("integerValue", take.startMs.coerceAtLeast(0).toString()))
            .put("lengthMs", JSONObject().put("integerValue", take.lengthMs.toString()))
            .put("at", JSONObject().put("timestampValue", java.time.Instant.now().toString())))
        val t0 = System.currentTimeMillis()
        call("PATCH", "$base/rank/${enc(songId)}/takes/$uid", token, body.toString(), "application/json", LONG_TIMEOUT)
        Log.i(TAG, "녹음 올림: $songId ${take.score}점 ${audio.size}바이트 (${System.currentTimeMillis() - t0}ms)")
    }

    /** 한 사람의 녹음을 받는다(요약, AAC 바이트). */
    suspend fun fetchTake(songId: String, uid: String): Pair<TakeMeta, ByteArray> = io {
        val t0 = System.currentTimeMillis()
        val d = JSONObject(call("GET", "$base/rank/${enc(songId)}/takes/${enc(uid)}", null, null, "application/json", LONG_TIMEOUT))
        val f = d.getJSONObject("fields")
        val bytes = android.util.Base64.decode(f.getJSONObject("audio").getString("bytesValue"), android.util.Base64.DEFAULT)
        Log.i(TAG, "녹음 받음: $songId ${bytes.size}바이트 (${System.currentTimeMillis() - t0}ms)")
        TakeMeta(int(f, "score").toInt(), int(f, "startMs"), int(f, "lengthMs")) to bytes
    }

    /** 서버의 내 녹음을 지운다. 올린 적이 없으면(익명 계정이 없으면) 할 일이 없다. */
    suspend fun deleteTake(songId: String) = io {
        if (prefs.getString("uid", null) == null) return@io
        val (uid, token) = auth()
        call("DELETE", "$base/rank/${enc(songId)}/takes/$uid", token, null, "application/json")
        Log.i(TAG, "서버의 내 녹음 지움: $songId")
    }

    // ---------- 익명 로그인 ----------

    private suspend fun auth(): Pair<String, String> = authLock.withLock {
        val uid = prefs.getString("uid", null)
        val refresh = prefs.getString("refresh", null)
        val now = System.currentTimeMillis()
        idToken?.let { if (uid != null && now < tokenUntil) return it.let { t -> uid to t } }
        val res = if (uid == null || refresh == null) {
            val r = post("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$key", JSONObject().put("returnSecureToken", true))
            prefs.edit().putString("uid", r.getString("localId")).putString("refresh", r.getString("refreshToken")).apply()
            Log.i(TAG, "익명 로그인 새로 함")
            Triple(r.getString("localId"), r.getString("idToken"), r.getString("expiresIn").toLong())
        } else {
            val r = postForm("https://securetoken.googleapis.com/v1/token?key=$key", "grant_type=refresh_token&refresh_token=${enc(refresh)}")
            prefs.edit().putString("refresh", r.getString("refresh_token")).apply()
            Triple(r.getString("user_id"), r.getString("id_token"), r.getString("expires_in").toLong())
        }
        idToken = res.second
        tokenUntil = now + (res.third - 60) * 1000
        res.first to res.second
    }

    // ---------- HTTP ----------

    private fun request(method: String, url: String, token: String?, body: JSONObject?): JSONObject =
        JSONObject(call(method, url, token, body?.toString(), "application/json"))

    private fun requestArray(method: String, url: String, token: String?, body: JSONObject?): JSONArray =
        JSONArray(call(method, url, token, body?.toString(), "application/json"))

    private fun post(url: String, body: JSONObject) = request("POST", url, null, body)

    private fun postForm(url: String, form: String) = JSONObject(call("POST", url, null, form, "application/x-www-form-urlencoded"))

    private fun call(method: String, url: String, token: String?, body: String?, type: String, timeoutMs: Int = 8000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection은 PATCH를 모른다: POST + 덮어쓰기 머리말로 보낸다
            c.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            c.connectTimeout = 8000
            c.readTimeout = timeoutMs
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            // API 키를 Android 앱으로 제한해 둔 경우를 위해 패키지와 서명 지문을 함께 보낸다
            c.setRequestProperty("X-Android-Package", context.packageName)
            certSha1?.let { c.setRequestProperty("X-Android-Cert", it) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type)
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw RankException(code, text.take(300))
            return text
        } finally {
            c.disconnect()
        }
    }

    private val certSha1: String? by lazy {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val sig = info.signingInfo.apkContentsSigners.first().toByteArray()
            MessageDigest.getInstance("SHA-1").digest(sig).joinToString("") { "%02X".format(it) }
        }.getOrNull()
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    class RankException(val code: Int, body: String) : Exception("HTTP $code $body")

    companion object {
        const val TAG = "SC.Rank"
        /** 녹음(약 1MB)을 주고받을 때 기다리는 시간. */
        const val LONG_TIMEOUT = 30_000
    }
}
