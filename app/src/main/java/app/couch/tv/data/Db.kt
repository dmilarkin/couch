package app.couch.tv.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteQuery

/** One movie, series or live channel from the provider. id looks like "movie:123". */
@Entity(
    tableName = "items",
    indices = [
        Index("kind", "hidden", "added"), Index("kind", "hidden", "rating"), Index("categoryId"), Index("groupKey"),
        Index("lang"), Index("year"),
    ],
)
data class Item(
    @PrimaryKey val id: String,
    val kind: String,
    val streamId: Long,
    val name: String,
    val title: String,
    val search: String,
    val year: Int,
    val poster: String?,
    val backdrop: String?,
    val rating: Double,
    val added: Long,
    val categoryId: String,
    val lang: String?,
    val quality: String?,
    val genre: String?,
    val ext: String?,
    val plot: String?,
    val groupKey: String,
    val num: Int,
    val epgId: String?,
    val tmdbId: String?,
    val syncId: Long,
    /** True when the language or category is switched off in the library filter. */
    val hidden: Boolean,
)

data class CatCount(val categoryId: String, val n: Int)
data class LangCount(val lang: String?, val n: Int)

@Entity(tableName = "categories", primaryKeys = ["kind", "id"])
data class Category(
    val kind: String,
    val id: String,
    val name: String,
    val lang: String?,
    val sort: Int,
    val adult: Boolean,
)

/** Where you left off. id is "mg:<movie group>" for movies or "ep:<episodeId>" for episodes. */
@Entity(tableName = "progress", indices = [Index("itemId"), Index("updatedAt")])
data class Progress(
    @PrimaryKey val id: String,
    val kind: String,
    val itemId: String,
    val episodeId: String?,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val season: Int,
    val episode: Int,
    val finished: Boolean,
    val upNext: Boolean,
)

@Entity(tableName = "favorites")
data class Favorite(@PrimaryKey val itemId: String, val addedAt: Long)

@Entity(tableName = "cache")
data class CacheEntry(@PrimaryKey val id: String, val json: String, val savedAt: Long)

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<Item>)

    @Query("DELETE FROM items WHERE kind = :kind AND syncId != :syncId")
    suspend fun deleteStale(kind: String, syncId: Long)

    @Query("SELECT COUNT(*) FROM items WHERE kind = :kind")
    suspend fun count(kind: String): Int

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: String): Item?

    @Query("SELECT * FROM items WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<Item>

    @Query("SELECT * FROM items WHERE kind = 'movie' AND groupKey = :groupKey AND hidden = 0")
    suspend fun versions(groupKey: String): List<Item>

    @Query("SELECT lang FROM items WHERE kind = :kind AND hidden = 0 AND lang IS NOT NULL GROUP BY lang ORDER BY COUNT(*) DESC")
    suspend fun langs(kind: String): List<String>

    @Query("SELECT DISTINCT (year / 10) * 10 AS decade FROM items WHERE kind = :kind AND hidden = 0 AND year > 1900 ORDER BY decade")
    suspend fun decades(kind: String): List<Int>

    @Query("SELECT * FROM items WHERE kind = 'live' AND hidden = 0 AND categoryId = :categoryId ORDER BY num, title")
    suspend fun channels(categoryId: String): List<Item>

    @Query("SELECT categoryId, COUNT(*) AS n FROM items WHERE kind = :kind GROUP BY categoryId")
    suspend fun categoryCounts(kind: String): List<CatCount>

    @Query("SELECT lang, COUNT(*) AS n FROM items GROUP BY lang ORDER BY n DESC")
    suspend fun langCounts(): List<LangCount>

    @Query("SELECT COUNT(*) FROM items WHERE kind = :kind AND hidden = 0")
    suspend fun visibleCount(kind: String): Int

    @Query("SELECT DISTINCT categoryId FROM items WHERE kind = :kind AND hidden = 0")
    suspend fun visibleCategoryIds(kind: String): List<String>

    @RawQuery
    suspend fun query(q: SupportSQLiteQuery): List<Item>
}

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: List<Category>)

    @Query("DELETE FROM categories WHERE kind = :kind")
    suspend fun clear(kind: String)

    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY sort")
    suspend fun list(kind: String): List<Category>

    @Query("SELECT * FROM categories WHERE kind = :kind AND id = :id")
    suspend fun get(kind: String, id: String): Category?
}

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: Progress)

    @Query("SELECT * FROM progress WHERE id = :id")
    suspend fun get(id: String): Progress?

    @Query("SELECT * FROM progress WHERE itemId = :itemId")
    suspend fun allFor(itemId: String): List<Progress>

    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT 300")
    suspend fun recent(): List<Progress>

    @Query("DELETE FROM progress WHERE itemId = :itemId AND upNext = 1")
    suspend fun clearUpNext(itemId: String)

    @Query("DELETE FROM progress WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(f: Favorite)

    @Query("DELETE FROM favorites WHERE itemId = :itemId")
    suspend fun remove(itemId: String)

    @Query("SELECT * FROM favorites WHERE itemId = :itemId")
    suspend fun get(itemId: String): Favorite?

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    suspend fun all(): List<Favorite>
}

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: CacheEntry)

    @Query("SELECT * FROM cache WHERE id = :id")
    suspend fun get(id: String): CacheEntry?

    @Query("DELETE FROM cache WHERE savedAt < :before")
    suspend fun prune(before: Long)
}

@Database(
    entities = [Item::class, Category::class, Progress::class, Favorite::class, CacheEntry::class],
    version = 1,
    exportSchema = false,
)
abstract class CouchDb : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun categories(): CategoryDao
    abstract fun progress(): ProgressDao
    abstract fun favorites(): FavoriteDao
    abstract fun cache(): CacheDao

    companion object {
        fun create(ctx: Context): CouchDb =
            Room.databaseBuilder(ctx, CouchDb::class.java, "couch.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
