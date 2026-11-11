package app.sichimi.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
)

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

    @Query("DELETE FROM Trip") suspend fun clearTrips()
    @Query("DELETE FROM SongPlay") suspend fun clearPlays()
    @Query("DELETE FROM ExposureEvent") suspend fun clearExposures()
}

@Database(entities = [Trip::class, SongPlay::class, ExposureEvent::class], version = 1, exportSchema = false)
abstract class RecordDb : RoomDatabase() {
    abstract fun dao(): RecordDao

    companion object {
        fun open(context: Context): RecordDb =
            Room.databaseBuilder(context, RecordDb::class.java, "records.db").build()
    }
}
