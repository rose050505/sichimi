package app.sichimi.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** 기어가 P를 벗어난 때부터 다시 P가 될 때까지. 시각은 System.currentTimeMillis(). */
@Entity
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val soloMs: Long = 0,
    val soloSongs: Int = 0,
    val exposures: Int = 0,
)

@Entity
data class SongPlay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long?,
    val songId: String,
    val startedAt: Long,
    val totalMs: Long,
    val soloMs: Long,
    /** 곡 길이(완창 판정). 3판에서 더함. */
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
    /** 와이퍼가 켜진 동안 혼자 부른 시간. */
    @ColumnInfo(defaultValue = "0") val rainSoloMs: Long = 0,
    /** 밤(19~6시)에 혼자 부른 시간. */
    @ColumnInfo(defaultValue = "0") val nightSoloMs: Long = 0,
)

/** cause: DOOR, SEAT, WINDOW. reactionMs: 신호부터 가수 음량이 오르기 시작한 때까지(잠시 복귀 중이었으면 null). */
@Entity
data class ExposureEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long?,
    val at: Long,
    val cause: String,
    val songId: String?,
    val positionMs: Long,
    val reactionMs: Long?,
    /** 그때 부르던 가사 한 줄(리플레이 카드). */
    val lyric: String? = null,
    /** 이 들킬 뻔 뒤 다시 노래방이 열린 시각(잘 숨고 돌아옴). */
    val relievedAt: Long? = null,
)

/**
 * 노래 점수 한 번. coverage: 곡의 음표 가운데 채점한 비율. finished: 절반 넘게 채점(완주). 최고 기록과 랭킹은 완주한 것만.
 */
@Entity
data class SongScore(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val at: Long,
    val score: Int,
    val coverage: Float,
    val finished: Boolean,
    /** 점수 화면을 보여 줬는가. 주행 중에 끝난 곡은 0으로 남았다가 주차 때 몰아서 보여 준다. */
    @ColumnInfo(defaultValue = "1") val revealed: Boolean = false,
    /** 고스트 대결 상대(없으면 null). */
    val ghostUid: String? = null,
    val ghostNickname: String? = null,
    val ghostScore: Int? = null,
)

/** 얻은 배지(kind=BADGE, week=""), 완료한 미션(MISSION, week=이번 주), 투어 완주(TOUR). shown: 주차 화면에서 알렸는가. */
@Entity(indices = [Index(value = ["kind", "key", "week"], unique = true)])
data class Award(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val key: String,
    val week: String,
    val at: Long,
    val points: Int,
    val shown: Boolean = false,
)

/** 고른 고스트. 목소리는 files/ghost/{songId}.pcm(16kHz 모노). 대결 한 판이 끝나면 지운다. */
@Entity
data class GhostPick(
    @PrimaryKey val songId: String,
    val uid: String,
    val nickname: String,
    val score: Int,
    val startMs: Long,
    val lengthMs: Long,
    val pickedAt: Long,
)

data class BestScore(val songId: String, val best: Int)

/** 곡별 내 점수: 최고(완주), 가장 최근, 부른 횟수. */
data class SongScoreRow(val songId: String, val best: Int?, val last: Int, val lastAt: Long, val times: Int)

data class SongCount(val songId: String, val plays: Int, val soloMs: Long)
data class CauseCount(val cause: String, val n: Int)

@Dao
interface RecordDao {
    @Insert suspend fun insertTrip(t: Trip): Long
    @Query("UPDATE Trip SET endedAt = :endedAt, soloMs = :soloMs, soloSongs = :soloSongs, exposures = :exposures WHERE id = :id")
    suspend fun finishTrip(id: Long, endedAt: Long, soloMs: Long, soloSongs: Int, exposures: Int)
    @Query("SELECT * FROM Trip WHERE id = :id") suspend fun trip(id: Long): Trip?

    @Insert suspend fun insertPlay(p: SongPlay): Long
    @Query("UPDATE SongPlay SET totalMs = :totalMs, soloMs = :soloMs WHERE id = :id")
    suspend fun updatePlay(id: Long, totalMs: Long, soloMs: Long)
    @Insert suspend fun insertExposure(e: ExposureEvent): Long

    @Query("SELECT MIN(reactionMs) FROM ExposureEvent WHERE tripId = :tripId AND reactionMs IS NOT NULL")
    suspend fun fastestReaction(tripId: Long): Long?

    // 내 기록: 이번 달
    @Query("SELECT COALESCE(SUM(soloMs), 0) FROM SongPlay WHERE startedAt >= :from")
    fun soloMsSince(from: Long): Flow<Long>
    @Query("SELECT COUNT(*) FROM SongPlay WHERE startedAt >= :from AND soloMs >= :minSoloMs")
    fun soloSongsSince(from: Long, minSoloMs: Long): Flow<Int>
    @Query("SELECT songId, COUNT(*) AS plays, SUM(soloMs) AS soloMs FROM SongPlay WHERE startedAt >= :from AND soloMs >= :minSoloMs GROUP BY songId ORDER BY plays DESC, soloMs DESC LIMIT 5")
    fun topSongsSince(from: Long, minSoloMs: Long): Flow<List<SongCount>>
    @Query("SELECT cause, COUNT(*) AS n FROM ExposureEvent WHERE at >= :from GROUP BY cause")
    fun causesSince(from: Long): Flow<List<CauseCount>>
    @Query("SELECT MIN(reactionMs) FROM ExposureEvent WHERE at >= :from AND reactionMs IS NOT NULL")
    fun fastestSince(from: Long): Flow<Long?>

    // 노래 점수
    @Insert suspend fun insertScore(s: SongScore): Long
    @Query("SELECT MAX(score) FROM SongScore WHERE songId = :songId AND finished = 1")
    suspend fun bestScore(songId: String): Int?
    @Query(
        "SELECT s.songId AS songId, " +
            "(SELECT MAX(score) FROM SongScore b WHERE b.songId = s.songId AND b.finished = 1) AS best, " +
            "s.score AS last, s.at AS lastAt, " +
            "(SELECT COUNT(*) FROM SongScore c WHERE c.songId = s.songId) AS times " +
            "FROM SongScore s WHERE s.at = (SELECT MAX(at) FROM SongScore l WHERE l.songId = s.songId) ORDER BY s.at DESC"
    )
    fun scoreRows(): Flow<List<SongScoreRow>>
    @Query("SELECT songId, score AS best FROM SongScore WHERE at >= :from AND finished = 1 ORDER BY score DESC, at ASC LIMIT 1")
    suspend fun bestSince(from: Long): BestScore?
    @Query("SELECT songId, MAX(score) AS best FROM SongScore WHERE finished = 1 GROUP BY songId")
    suspend fun bestScores(): List<BestScore>
    @Query("DELETE FROM SongScore") suspend fun clearScores()

    // ---------- v2: 무대 ----------
    @Query("UPDATE SongPlay SET totalMs = :totalMs, soloMs = :soloMs, rainSoloMs = :rainSoloMs, nightSoloMs = :nightSoloMs WHERE id = :id")
    suspend fun updatePlayEnv(id: Long, totalMs: Long, soloMs: Long, rainSoloMs: Long, nightSoloMs: Long)
    @Query("SELECT * FROM SongPlay") fun allPlays(): Flow<List<SongPlay>>
    @Query("SELECT * FROM ExposureEvent") fun allExposures(): Flow<List<ExposureEvent>>
    @Query("SELECT * FROM SongScore") fun allScores(): Flow<List<SongScore>>
    @Query("SELECT * FROM Award ORDER BY at") fun allAwards(): Flow<List<Award>>
    @Query("SELECT * FROM ExposureEvent WHERE tripId = :tripId ORDER BY at") suspend fun exposuresOfTrip(tripId: Long): List<ExposureEvent>
    @Query("SELECT * FROM ExposureEvent WHERE id = :id") suspend fun exposure(id: Long): ExposureEvent?
    @Query("UPDATE ExposureEvent SET relievedAt = :at WHERE id = :id AND relievedAt IS NULL") suspend fun markRelieved(id: Long, at: Long)
    @Query("SELECT * FROM SongScore WHERE revealed = 0 ORDER BY id") fun unrevealedScores(): Flow<List<SongScore>>
    @Query("SELECT * FROM SongScore WHERE id = :id") suspend fun score(id: Long): SongScore?
    @Query("UPDATE SongScore SET revealed = 1 WHERE id = :id") suspend fun markRevealed(id: Long)
    @Query("SELECT * FROM Award WHERE shown = 0 ORDER BY id") fun unshownAwards(): Flow<List<Award>>
    @Query("UPDATE Award SET shown = 1 WHERE id = :id") suspend fun markAwardShown(id: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAward(a: Award): Long
    @Query("SELECT * FROM GhostPick") fun ghostPicks(): Flow<List<GhostPick>>
    @Query("SELECT * FROM GhostPick WHERE songId = :songId") suspend fun ghostPick(songId: String): GhostPick?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putGhost(g: GhostPick)
    @Query("DELETE FROM GhostPick WHERE songId = :songId") suspend fun deleteGhost(songId: String)
    @Query("DELETE FROM Award") suspend fun clearAwards()
    @Query("DELETE FROM GhostPick") suspend fun clearGhosts()

    @Query("DELETE FROM Trip") suspend fun clearTrips()
    @Query("DELETE FROM SongPlay") suspend fun clearPlays()
    @Query("DELETE FROM ExposureEvent") suspend fun clearExposures()
}

@Database(entities = [Trip::class, SongPlay::class, ExposureEvent::class, SongScore::class, Award::class, GhostPick::class], version = 3, exportSchema = false)
abstract class RecordDb : RoomDatabase() {
    abstract fun dao(): RecordDao

    companion object {
        fun open(context: Context): RecordDb =
            Room.databaseBuilder(context, RecordDb::class.java, "records.db").addMigrations(V1_TO_V2, V2_TO_V3).build()

        /** 2 -> 3: 무대(v2). 기존 기록은 그대로 두고 열과 표를 더한다. 옛 점수는 이미 본 것으로(revealed=1) 둔다. */
        val V2_TO_V3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `SongPlay` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `SongPlay` ADD COLUMN `rainSoloMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `SongPlay` ADD COLUMN `nightSoloMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `ExposureEvent` ADD COLUMN `lyric` TEXT")
                db.execSQL("ALTER TABLE `ExposureEvent` ADD COLUMN `relievedAt` INTEGER")
                db.execSQL("ALTER TABLE `SongScore` ADD COLUMN `revealed` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `SongScore` ADD COLUMN `ghostUid` TEXT")
                db.execSQL("ALTER TABLE `SongScore` ADD COLUMN `ghostNickname` TEXT")
                db.execSQL("ALTER TABLE `SongScore` ADD COLUMN `ghostScore` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Award` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `key` TEXT NOT NULL, " +
                        "`week` TEXT NOT NULL, `at` INTEGER NOT NULL, `points` INTEGER NOT NULL, `shown` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Award_kind_key_week` ON `Award` (`kind`, `key`, `week`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `GhostPick` (`songId` TEXT NOT NULL, `uid` TEXT NOT NULL, `nickname` TEXT NOT NULL, `score` INTEGER NOT NULL, " +
                        "`startMs` INTEGER NOT NULL, `lengthMs` INTEGER NOT NULL, `pickedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))"
                )
            }
        }

        /** 1 -> 2: 노래 점수 표를 더한다. 기존 기록은 그대로 둔다. */
        val V1_TO_V2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `SongScore` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`songId` TEXT NOT NULL, `at` INTEGER NOT NULL, `score` INTEGER NOT NULL, " +
                        "`coverage` REAL NOT NULL, `finished` INTEGER NOT NULL)"
                )
            }
        }
    }
}
