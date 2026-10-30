package neth.iecal.curbox.reader

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val status: String = SeriesStatus.READING.id,
    /** MangaDex series id, once linked (from a Kenmei import or a confirmed match). */
    val mangadexId: String? = null,
    /** A MangaDex series found by title, waiting for the owner to confirm. */
    val mangadexCandidateId: String? = null,
    val mangadexCandidateTitle: String? = null,
    /** The owner said the found MangaDex series is not this one; do not ask again. */
    @ColumnInfo(defaultValue = "0") val mangadexDeclined: Boolean = false,
    @ColumnInfo(defaultValue = "0") val mangadexSearchedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val lastCheckedAt: Long = 0L,
    /** The last check for new chapters found nothing it could read. */
    @ColumnInfo(defaultValue = "0") val checkFailed: Boolean = false,
    /** When this series' other MangaDex names were last saved as aliases. */
    @ColumnInfo(defaultValue = "0") val mangadexTitlesAt: Long = 0L
)

/** Reading time and new chapters opened on one day (local date, like 2026-10-30). */
@Entity(tableName = "reading_days")
data class ReadingDayEntity(
    @PrimaryKey val date: String,
    val readingTimeMs: Long,
    val chapters: Int
)

/** Two series the owner said are not the same; keys in alphabetical order. */
@Entity(tableName = "not_duplicates", primaryKeys = ["keyA", "keyB"])
data class NotDuplicateEntity(
    val keyA: String,
    val keyB: String
)

/** A released chapter found by the new chapter check, with the address to open it. */
@Entity(tableName = "available_chapters", primaryKeys = ["seriesKey", "chapter"])
data class AvailableChapterEntity(
    val seriesKey: String,
    val chapter: Double,
    val url: String
)

/** A series with what the new chapter check found after its last chapter read. */
data class SeriesWithReleases(
    @Embedded val series: SeriesEntity,
    val newCount: Int,
    val latestChapter: Double?,
    val nextChapter: Double?,
    val nextUrl: String?
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

    @Query(
        "SELECT s.*, " +
            "(SELECT COUNT(*) FROM available_chapters a WHERE a.seriesKey = s.`key` AND a.chapter > s.lastChapter) AS newCount, " +
            "(SELECT MAX(a.chapter) FROM available_chapters a WHERE a.seriesKey = s.`key`) AS latestChapter, " +
            "(SELECT MIN(a.chapter) FROM available_chapters a WHERE a.seriesKey = s.`key` AND a.chapter > s.lastChapter) AS nextChapter, " +
            "(SELECT a.url FROM available_chapters a WHERE a.seriesKey = s.`key` AND a.chapter > s.lastChapter ORDER BY a.chapter LIMIT 1) AS nextUrl " +
            "FROM series s ORDER BY s.lastReadAt DESC"
    )
    fun observeSeriesWithReleases(): Flow<List<SeriesWithReleases>>

    @Query("SELECT * FROM series")
    suspend fun allSeries(): List<SeriesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAvailableChapters(chapters: List<AvailableChapterEntity>)

    @Query("DELETE FROM available_chapters WHERE seriesKey = :key")
    suspend fun deleteAvailableChapters(key: String)

    @Query("UPDATE OR REPLACE available_chapters SET seriesKey = :toKey WHERE seriesKey = :fromKey")
    suspend fun moveAvailableChapters(fromKey: String, toKey: String)

    /** Replaces what the check knows about [key]; [chapters] later in the list win. */
    @Transaction
    suspend fun replaceAvailableChapters(key: String, chapters: List<AvailableChapterEntity>) {
        deleteAvailableChapters(key)
        putAvailableChapters(chapters)
    }

    @Query("UPDATE series SET lastCheckedAt = :at, checkFailed = :failed WHERE `key` = :key")
    suspend fun setChecked(key: String, at: Long, failed: Boolean)

    @Query("UPDATE series SET mangadexCandidateId = :id, mangadexCandidateTitle = :title, mangadexSearchedAt = :at WHERE `key` = :key")
    suspend fun setMangadexCandidate(key: String, id: String?, title: String?, at: Long)

    @Query("UPDATE series SET mangadexId = :id, mangadexCandidateId = NULL, mangadexCandidateTitle = NULL WHERE `key` = :key")
    suspend fun linkMangadex(key: String, id: String?)

    @Query("UPDATE series SET mangadexCandidateId = NULL, mangadexCandidateTitle = NULL, mangadexDeclined = 1 WHERE `key` = :key")
    suspend fun declineMangadex(key: String)

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
                        status = entry.status.id,
                        mangadexId = MangaDex.idFromUrl(entry.url)
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
                        status = entry.status.id,
                        mangadexId = existing.mangadexId ?: MangaDex.idFromUrl(entry.url)
                    )
                )
            }
        }
        return added
    }

    @Query("UPDATE OR REPLACE chapter_reads SET seriesKey = :toKey WHERE seriesKey = :fromKey")
    suspend fun moveChapterReads(fromKey: String, toKey: String)



    @Query("DELETE FROM series WHERE `key` = :key")
    suspend fun deleteSeriesRow(key: String)

    @Query("DELETE FROM chapter_reads WHERE seriesKey = :key")
    suspend fun deleteChapterReads(key: String)

    @Query("DELETE FROM series_alias WHERE fromKey = :key OR toKey = :key")
    suspend fun deleteAliases(key: String)

    /**
     * Moves everything from [fromKey] into [toKey] and remembers it for later reads. The
     * target keeps its name; the chapter comes from whichever was read more recently, reading
     * times add up, and a MangaDex link is kept from either.
     */
    @Transaction
    suspend fun merge(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        val target = series(toKey) ?: return
        val source = series(fromKey)
        moveChapterReads(fromKey, toKey)
        moveAvailableChapters(fromKey, toKey)
        repointAliases(fromKey, toKey)
        putAlias(SeriesAliasEntity(fromKey, toKey))
        deleteSeriesRow(fromKey)
        val newer = if (source != null && source.lastReadAt > target.lastReadAt) source else target
        val status = if (target.status == SeriesStatus.PLAN_TO_READ.id && source?.status == SeriesStatus.READING.id) {
            SeriesStatus.READING.id
        } else {
            target.status
        }
        putSeries(
            target.copy(
                lastChapter = newer.lastChapter,
                lastUrl = newer.lastUrl.ifEmpty { target.lastUrl },
                lastSite = newer.lastSite.ifEmpty { target.lastSite },
                lastReadAt = newer.lastReadAt,
                readingTimeMs = target.readingTimeMs + (source?.readingTimeMs ?: 0L),
                status = status,
                mangadexId = target.mangadexId ?: source?.mangadexId
            )
        )
    }

    @Query("SELECT * FROM series WHERE mangadexId = :id LIMIT 1")
    suspend fun seriesByMangadexId(id: String): SeriesEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putAliasIfAbsent(alias: SeriesAliasEntity)

    @Query("UPDATE series SET mangadexTitlesAt = :at WHERE `key` = :key")
    suspend fun setMangadexTitlesAt(key: String, at: Long)

    @Query("INSERT OR IGNORE INTO reading_days (date, readingTimeMs, chapters) VALUES (:date, 0, 0)")
    suspend fun ensureDay(date: String)

    @Query("UPDATE reading_days SET readingTimeMs = readingTimeMs + :ms WHERE date = :date")
    suspend fun addDayTimeRow(date: String, ms: Long)

    @Query("UPDATE reading_days SET chapters = chapters + 1 WHERE date = :date")
    suspend fun addDayChapterRow(date: String)

    @Transaction
    suspend fun addDayTime(date: String, ms: Long) {
        ensureDay(date)
        addDayTimeRow(date, ms)
    }

    @Transaction
    suspend fun addDayChapter(date: String) {
        ensureDay(date)
        addDayChapterRow(date)
    }

    @Query("SELECT * FROM reading_days WHERE date >= :fromDate ORDER BY date")
    suspend fun daysFrom(fromDate: String): List<ReadingDayEntity>

    @Query("SELECT * FROM reading_days WHERE date = :date")
    suspend fun day(date: String): ReadingDayEntity?

    @Query("SELECT * FROM not_duplicates")
    fun observeNotDuplicates(): Flow<List<NotDuplicateEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putNotDuplicate(pair: NotDuplicateEntity)

    @Transaction
    suspend fun delete(key: String) {
        deleteSeriesRow(key)
        deleteChapterReads(key)
        deleteAvailableChapters(key)
        deleteAliases(key)
    }
}

/**
 * Reading history lives in its own file, apart from [neth.iecal.curbox.data.db.AppDatabase],
 * whose structure changes wipe it. Change this one only with real migrations: the history is
 * the whole point of the feature.
 */
@Database(
    entities = [
        SeriesEntity::class, ChapterReadEntity::class, SeriesAliasEntity::class,
        AvailableChapterEntity::class, NotDuplicateEntity::class, ReadingDayEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class ReadingDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao

    companion object {
        @Volatile
        private var INSTANCE: ReadingDatabase? = null

        // 2.2.0 -> new chapter checks (MangaDex links, check results, released chapters) and
        // duplicate handling (other MangaDex names, pairs the owner said are different).
        // 2.3.0 -> reading stats per day.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_days` (`date` TEXT NOT NULL, " +
                        "`readingTimeMs` INTEGER NOT NULL, `chapters` INTEGER NOT NULL, PRIMARY KEY(`date`))"
                )
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE series ADD COLUMN mangadexId TEXT")
                db.execSQL("ALTER TABLE series ADD COLUMN mangadexCandidateId TEXT")
                db.execSQL("ALTER TABLE series ADD COLUMN mangadexCandidateTitle TEXT")
                db.execSQL("ALTER TABLE series ADD COLUMN mangadexDeclined INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series ADD COLUMN mangadexSearchedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series ADD COLUMN lastCheckedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series ADD COLUMN checkFailed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series ADD COLUMN mangadexTitlesAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `not_duplicates` (`keyA` TEXT NOT NULL, `keyB` TEXT NOT NULL, " +
                        "PRIMARY KEY(`keyA`, `keyB`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `available_chapters` (`seriesKey` TEXT NOT NULL, " +
                        "`chapter` REAL NOT NULL, `url` TEXT NOT NULL, PRIMARY KEY(`seriesKey`, `chapter`))"
                )
            }
        }

        fun getInstance(context: Context): ReadingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReadingDatabase::class.java,
                    "reading_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .enableMultiInstanceInvalidation()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
