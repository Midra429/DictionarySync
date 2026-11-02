package me.midra.dictionarysync.data.local

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.*
import java.io.File
import me.midra.dictionarysync.sync.PendingRequest
import me.midra.dictionarysync.sync.PendingRequestDao
import me.midra.dictionarysync.sync.PlanKey
import me.midra.dictionarysync.sync.ProviderBinding
import me.midra.dictionarysync.sync.ReferenceGeneration
import me.midra.dictionarysync.sync.SourceReference
import me.midra.dictionarysync.sync.SyncDao
import me.midra.dictionarysync.sync.SyncInstallation
import me.midra.dictionarysync.sync.SyncKey
import me.midra.dictionarysync.sync.SyncOperation
import me.midra.dictionarysync.sync.SyncPlan
import kotlinx.coroutines.flow.Flow

/**
 * A registered dictionary file and its settings. [revision] changes with any setting that affects fetching, so a
 * generation fetched with old settings never mixes into new ones. [latestSnapshot] is the newest valid generation,
 * [activeSnapshot] the one in the shared dictionary. Active URL uniqueness is checked under the sync lock, since a
 * deleted source keeps its row and must not reserve its URL.
 */
@Entity(tableName = "sources", indices = [Index(value = ["url"])])
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val locale: String = "AUTO",
    val format: String = "AUTO",
    val encoding: String = "AUTO",
    val revision: Long = 1,
    val latestSnapshot: String? = null,
    val previousSnapshot: String? = null,
    val activeSnapshot: String? = null,
    /** The last fetch; [error] is its reason. */
    val state: FetchState = FetchState.NOT_FETCHED,
    val error: String? = null,
    /** Last successful fetch, including a validated 304. */
    val fetchedAt: Long? = null,
    val syncedAt: Long? = null,
    /** Last fetch whose content differed from the previous generation. */
    val changedAt: Long? = null,
    val lifecycle: SourceLifecycle = SourceLifecycle.ACTIVE,
    /** The shared dictionary; [syncError] is its reason. */
    val syncState: SyncState = SyncState.NOT_SYNCED,
    /** Retry-After from the server, which every fetch of this source respects. */
    val serverNotBefore: Long = 0,
    val enabled: Boolean = true,
    val syncIntervalHours: Int = 24,
    val syncError: String? = null,
) {
    val listed get() = lifecycle == SourceLifecycle.ACTIVE
    /** Fetched and kept in the shared dictionary. */
    val syncable get() = listed && enabled
    /** Whether the fetch interval has passed since the last successful fetch. */
    fun fetchDue(now: Long) = fetchedAt == null || now - fetchedAt >= syncIntervalHours * 3_600_000L
}

@Entity(tableName = "snapshots", indices = [Index("sourceId")], foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"])])
data class SnapshotEntity(
    @PrimaryKey val id: String, val sourceId: String, val revision: Long, val bodyHash: String, val contentHash: String,
    val fetchedAt: Long, val finalUrl: String, val parser: String, val encoding: String, val bytes: Int, val lines: Int,
    val count: Int, val uniqueCount: Int,
    // The configuration this generation was fetched with, and the locale entries without a declared language resolve to.
    val sourceLocale: String, val sourceUrl: String, val sourceFormat: String, val sourceEncoding: String,
    val etag: String? = null, val lastModified: String? = null,
    val validationVersion: String, val resolvedLocale: String,
    /** Lines of a format-confirmed file skipped for having too few columns, and the first of them (shown with the words). */
    val skippedLines: Int = 0, val firstSkippedLine: Int? = null,
)

data class SourceCount(val sourceId: String, val count: Int)

@Entity(tableName = "entries", primaryKeys = ["snapshotId", "line"], indices = [Index(value = ["snapshotId", "word"]), Index(value = ["snapshotId", "reading"])],
    foreignKeys = [ForeignKey(entity = SnapshotEntity::class, parentColumns = ["id"], childColumns = ["snapshotId"])])
data class EntryEntity(val snapshotId: String, val line: Int, val reading: String, val word: String, val partOfSpeech: String, val comment: String,
    /** Resolved language of this entry (SourceRules.entryLocale); part of its shared-dictionary key. */
    val locale: String)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM sources WHERE lifecycle != 'DELETED' ORDER BY name, id") fun observeSources(): Flow<List<SourceEntity>>
    @Query("SELECT * FROM sources WHERE lifecycle != 'DELETED' ORDER BY id") suspend fun sources(): List<SourceEntity>
    @Query("SELECT * FROM sources ORDER BY id") suspend fun allSources(): List<SourceEntity>
    @Query("SELECT * FROM sources WHERE id=:id") suspend fun source(id: String): SourceEntity?
    @Query("SELECT s.id AS sourceId, p.count FROM sources s JOIN snapshots p ON p.id=s.latestSnapshot WHERE s.lifecycle != 'DELETED'")
    fun observeCounts(): Flow<List<SourceCount>>
    @Query("SELECT * FROM sources WHERE url=:url AND lifecycle='DELETED' LIMIT 1") suspend fun archivedUrl(url: String): SourceEntity?
    @Insert suspend fun addSource(source: SourceEntity)
    @Update suspend fun updateSource(source: SourceEntity)
    @Query("SELECT * FROM snapshots WHERE id=:id") suspend fun snapshot(id: String): SnapshotEntity?
    @Query("SELECT * FROM snapshots WHERE sourceId=:sourceId ORDER BY fetchedAt DESC") fun snapshots(sourceId: String): Flow<List<SnapshotEntity>>
    @Insert suspend fun addSnapshot(snapshot: SnapshotEntity)
    @Insert suspend fun addEntries(entries: List<EntryEntity>)
    @Query("SELECT * FROM entries WHERE snapshotId=:snapshot ORDER BY line") suspend fun entries(snapshot: String): List<EntryEntity>
    @Query("""SELECT * FROM entries WHERE snapshotId=:snapshot AND (word LIKE :query ESCAPE '\' OR reading LIKE :query ESCAPE '\') ORDER BY line""")
    fun search(snapshot: String, query: String): PagingSource<Int, EntryEntity>
    @Query("SELECT DISTINCT locale FROM entries WHERE snapshotId=:snapshot ORDER BY locale") fun locales(snapshot: String): Flow<List<String>>
    @Query("DELETE FROM entries WHERE snapshotId=:snapshot") suspend fun deleteEntries(snapshot: String)
    @Query("DELETE FROM snapshots WHERE id=:snapshot") suspend fun deleteSnapshot(snapshot: String)
    @Query("SELECT id FROM snapshots WHERE sourceId=:sourceId") suspend fun snapshotIds(sourceId: String): List<String>
    @Query("DELETE FROM sources WHERE id=:id") suspend fun deleteSource(id: String)
}

@Database(entities = [SourceEntity::class, SnapshotEntity::class, EntryEntity::class, SyncKey::class, ProviderBinding::class, SourceReference::class, SyncPlan::class, PlanKey::class, SyncOperation::class, SyncInstallation::class, PendingRequest::class, ReferenceGeneration::class], version = 1, exportSchema = true)
abstract class DictionaryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun sync(): SyncDao
    abstract fun requests(): PendingRequestDao
    companion object {
        @Volatile private var instance: DictionaryDatabase? = null
        fun get(context: Context): DictionaryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, DictionaryDatabase::class.java,
                File(context.noBackupFilesDir, "library.db").absolutePath).build().also { instance = it }
        }
    }
}
