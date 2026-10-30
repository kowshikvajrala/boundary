package neth.iecal.curbox.reader

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** One series the owner reads, with where they got to. */
@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val key: String,
    val name: String,
    val lastChapter: Double,
    val lastUrl: String,
    val lastSite: String,
    val lastReadAt: Long,
    val readingTimeMs: Long,
    /** A [SeriesStatus] id. */
    val status: String = SeriesStatus.READING.id
)

/** One chapter opened, with the time spent on it. */
@Entity(tableName = "chapter_reads", primaryKeys = ["seriesKey", "chapter"])
data class ChapterReadEntity(
    val seriesKey: String,
    val chapter: Double,
    val url: String,
    val site: String,
    val firstOpenedAt: Long,
    val lastOpenedAt: Long,
    val readingTimeMs: Long
)

/** A series merged into another one; later reads of [fromKey] count for [toKey]. */
@Entity(tableName = "series_alias")
data class SeriesAliasEntity(
    @PrimaryKey val fromKey: String,
    val toKey: String
)

@Dao
interface ReadingDao {
    @Query("SELECT * FROM series ORDER BY lastReadAt DESC")
    fun observeSeries(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE `key` = :key")
    suspend fun series(key: String): SeriesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSeries(series: SeriesEntity)

    @Query("SELECT * FROM chapter_reads WHERE seriesKey = :key AND chapter = :chapter")
    suspend fun chapterRead(key: String, chapter: Double): ChapterReadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putChapterRead(read: ChapterReadEntity)

    @Query("SELECT toKey FROM series_alias WHERE fromKey = :key")
    suspend fun aliasOf(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAlias(alias: SeriesAliasEntity)

    @Query("UPDATE series_alias SET toKey = :toKey WHERE toKey = :fromKey")
    suspend fun repointAliases(fromKey: String, toKey: String)

    @Query("UPDATE series SET name = :name WHERE `key` = :key")
    suspend fun rename(key: String, name: String)

    @Query("UPDATE series SET status = :status WHERE `key` = :key")
    suspend fun setStatus(key: String, status: String)

    @Query("UPDATE series SET lastChapter = :chapter WHERE `key` = :key")
    suspend fun setChapter(key: String, chapter: Double)

    /**
     * Adds a Kenmei list. A series already here keeps its name, gets the imported status, and
     * keeps whichever chapter is further along. Returns how many series were new.
     */
    @Transaction
    suspend fun importEntries(entries: List<KenmeiImport.Entry>, now: Long): Int {
        var added = 0
        entries.forEach { entry ->
            val baseKey = SeriesKeys.of(entry.title).takeIf { it.isNotEmpty() } ?: return@forEach
            val key = aliasOf(baseKey) ?: baseKey
            val existing = series(key)
            val readAt = entry.lastReadAt.takeIf { it > 0L } ?: now
            if (existing == null) {
                added++
                putSeries(
                    SeriesEntity(
                        key = key,
                        name = entry.title,
                        lastChapter = entry.chapter,
                        lastUrl = entry.url,
                        lastSite = KenmeiImport.siteOf(entry.url),
                        lastReadAt = readAt,
                        readingTimeMs = 0L,
                        status = entry.status.id
                    )
                )
            } else {
                val importedIsAhead = entry.chapter > existing.lastChapter
                putSeries(
                    existing.copy(
                        lastChapter = maxOf(existing.lastChapter, entry.chapter),
                        lastUrl = if (importedIsAhead && entry.url.isNotEmpty()) entry.url else existing.lastUrl,
                        lastSite = if (importedIsAhead && entry.url.isNotEmpty()) KenmeiImport.siteOf(entry.url) else existing.lastSite,
                        lastReadAt = maxOf(existing.lastReadAt, entry.lastReadAt),
                        status = entry.status.id
                    )
                )
            }
        }
        return added
    }

    @Query("UPDATE OR REPLACE chapter_reads SET seriesKey = :toKey WHERE seriesKey = :fromKey")
    suspend fun moveChapterReads(fromKey: String, toKey: String)

    @Query("SELECT * FROM chapter_reads WHERE seriesKey = :key ORDER BY lastOpenedAt DESC LIMIT 1")
    suspend fun latestChapterRead(key: String): ChapterReadEntity?

    @Query("SELECT COALESCE(SUM(readingTimeMs), 0) FROM chapter_reads WHERE seriesKey = :key")
    suspend fun totalReadingTime(key: String): Long

    @Query("DELETE FROM series WHERE `key` = :key")
    suspend fun deleteSeriesRow(key: String)

    @Query("DELETE FROM chapter_reads WHERE seriesKey = :key")
    suspend fun deleteChapterReads(key: String)

    @Query("DELETE FROM series_alias WHERE fromKey = :key OR toKey = :key")
    suspend fun deleteAliases(key: String)

    /** Moves everything from [fromKey] into [toKey] and remembers it for later reads. */
    @Transaction
    suspend fun merge(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        val target = series(toKey) ?: return
        moveChapterReads(fromKey, toKey)
        repointAliases(fromKey, toKey)
        putAlias(SeriesAliasEntity(fromKey, toKey))
        deleteSeriesRow(fromKey)
        val latest = latestChapterRead(toKey)
        putSeries(
            target.copy(
                lastChapter = latest?.chapter ?: target.lastChapter,
                lastUrl = latest?.url ?: target.lastUrl,
                lastSite = latest?.site ?: target.lastSite,
                lastReadAt = latest?.lastOpenedAt ?: target.lastReadAt,
                readingTimeMs = totalReadingTime(toKey)
            )
        )
    }

    @Transaction
    suspend fun delete(key: String) {
        deleteSeriesRow(key)
        deleteChapterReads(key)
        deleteAliases(key)
    }
}

/**
 * Reading history lives in its own file, apart from [neth.iecal.curbox.data.db.AppDatabase],
 * whose structure changes wipe it. Change this one only with real migrations: the history is
 * the whole point of the feature.
 */
@Database(
    entities = [SeriesEntity::class, ChapterReadEntity::class, SeriesAliasEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ReadingDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao

    companion object {
        @Volatile
        private var INSTANCE: ReadingDatabase? = null

        fun getInstance(context: Context): ReadingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReadingDatabase::class.java,
                    "reading_db"
                ).enableMultiInstanceInvalidation()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
