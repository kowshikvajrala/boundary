package neth.iecal.curbox.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ScrollPatternDao {

    @Query("SELECT * FROM scroll_pattern WHERE packageName = :pkg")
    suspend fun getPattern(pkg: String): ScrollPatternEntity?

    @Upsert
    suspend fun upsert(entity: ScrollPatternEntity)
}
