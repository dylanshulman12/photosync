package com.photosync.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * On-device cache. Two jobs:
 *  1. remember the SHA-256 of each local file so we never re-hash it (hashing a
 *     whole photo/video library is the expensive part, so we key the cache on
 *     mediaId + dateModified + size and only compute on a miss).
 *  2. remember which hashes the server already has (synced), so the gallery can
 *     paint a badge instantly and update live as a sync runs.
 */
@Entity(tableName = "cache")
data class CacheEntry(
    @PrimaryKey val mediaId: Long,
    val dateModified: Long,
    val size: Long,
    val hash: String,
    val synced: Boolean
)

/** Media the user has chosen NOT to sync. Kept in its own table so it persists
 *  even for files that have never been hashed, and survives cache changes. */
@Entity(tableName = "excluded")
data class ExcludedItem(
    @PrimaryKey val mediaId: Long
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE mediaId = :id LIMIT 1")
    suspend fun get(id: Long): CacheEntry?

    @Query("SELECT * FROM cache")
    suspend fun all(): List<CacheEntry>

    /** live map of mediaId -> synced for the gallery */
    @Query("SELECT * FROM cache")
    fun flowAll(): Flow<List<CacheEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: CacheEntry)

    @Query("UPDATE cache SET synced = 1 WHERE hash = :hash")
    suspend fun markSyncedByHash(hash: String)

    @Query("SELECT hash FROM cache")
    suspend fun knownHashes(): List<String>

    // ---- exclusions ----
    @Query("SELECT mediaId FROM excluded")
    suspend fun excludedIds(): List<Long>

    /** live list of excluded mediaIds for the gallery */
    @Query("SELECT mediaId FROM excluded")
    fun flowExcluded(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun exclude(e: ExcludedItem)

    @Query("DELETE FROM excluded WHERE mediaId = :id")
    suspend fun unexclude(id: Long)
}

@Database(entities = [CacheEntry::class, ExcludedItem::class], version = 2, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun cache(): CacheDao

    companion object {
        /** v1 -> v2: add the excluded table. Creating only the new table keeps
         *  every existing hash in the cache intact (no destructive migration). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS excluded (mediaId INTEGER NOT NULL PRIMARY KEY)")
            }
        }

        @Volatile private var I: Db? = null
        fun get(ctx: Context): Db = I ?: synchronized(this) {
            I ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "cache.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { I = it }
        }
    }
}
