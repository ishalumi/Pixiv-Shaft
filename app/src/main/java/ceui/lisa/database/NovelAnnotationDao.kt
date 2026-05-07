package ceui.lisa.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface NovelAnnotationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NovelAnnotationEntity): Long

    @Update
    suspend fun update(entity: NovelAnnotationEntity)

    @Delete
    suspend fun delete(entity: NovelAnnotationEntity)

    @Query("DELETE FROM novel_annotation_table WHERE annotationId = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM novel_annotation_table WHERE novelId = :novelId")
    suspend fun deleteAllForNovel(novelId: Long): Int

    @Query("SELECT * FROM novel_annotation_table WHERE novelId = :novelId ORDER BY charStart ASC")
    suspend fun getForNovel(novelId: Long): List<NovelAnnotationEntity>

    @Query("SELECT * FROM novel_annotation_table WHERE novelId = :novelId ORDER BY charStart ASC")
    fun observeForNovel(novelId: Long): LiveData<List<NovelAnnotationEntity>>

    @Query("SELECT * FROM novel_annotation_table WHERE novelId = :novelId AND charStart < :charEnd AND charEnd > :charStart")
    suspend fun findOverlapping(novelId: Long, charStart: Int, charEnd: Int): List<NovelAnnotationEntity>

    /**
     * Exact-range lookup, scoped by [kind] — used by `addHighlight` to fold
     * a re-pick of color into an UPDATE on the existing row instead of a
     * second INSERT. Returns at most one row in normal use; if older builds
     * left duplicates, the newest (highest annotationId) wins.
     */
    @Query(
        "SELECT * FROM novel_annotation_table " +
            "WHERE novelId = :novelId AND charStart = :charStart AND charEnd = :charEnd AND kind = :kind " +
            "ORDER BY annotationId DESC LIMIT 1",
    )
    suspend fun findExactRange(
        novelId: Long,
        charStart: Int,
        charEnd: Int,
        kind: Int,
    ): NovelAnnotationEntity?

    @Query("SELECT * FROM novel_annotation_table ORDER BY updatedTime DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NovelAnnotationEntity>

    @Query("SELECT COUNT(*) FROM novel_annotation_table WHERE novelId = :novelId")
    suspend fun countForNovel(novelId: Long): Int
}
