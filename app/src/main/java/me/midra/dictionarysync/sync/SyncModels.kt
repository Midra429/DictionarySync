package me.midra.dictionarysync.sync

import androidx.room.*
import java.io.*
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import me.midra.dictionarysync.probe.ProbeRow
import me.midra.dictionarysync.probe.ProbeValue

/** Length-prefixed UTF-8 preserves null, empty, Unicode and tuple boundaries. Hash is auxiliary only. */
object SyncCodec {
    fun pack(values: List<String?>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out -> values.forEach { s ->
            if (s == null) out.writeInt(-1) else s.toByteArray(Charsets.UTF_8).let { out.writeInt(it.size); out.write(it) }
        } }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
    fun unpack(value: String): List<String?> = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use { input ->
        buildList { while (input.available() > 0) {
            val size = input.readInt()
            require(size in -1..1_000_000)
            add(if (size == -1) null else ByteArray(size).also(input::readFully).toString(Charsets.UTF_8))
        } }
    }
    fun row(row: ProbeRow): String = pack(listOf(row.id.toString(), row.word, row.shortcut, row.locale, row.frequency?.toString(), row.appId?.toString()))
    fun row(value: String): ProbeRow = unpack(value).let { require(it.size == 6); ProbeRow(it[0]!!.toLong(), it[1]!!, it[2], it[3], it[4]?.toInt(), it[5]?.toInt()) }
    fun hash(values: List<String?>): String = digest(pack(values))
    /** SHA-256 of an already packed value, as lower-case hex. Same result as [hash] of the unpacked values. */
    fun digest(packed: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(packed.toByteArray(Charsets.US_ASCII))
        val hex = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b -> hex[i * 2] = HEX[(b.toInt() shr 4) and 15]; hex[i * 2 + 1] = HEX[b.toInt() and 15] }
        return String(hex)
    }
    private const val HEX = "0123456789abcdef"
}

@Entity(tableName = "entry_keys", indices = [Index("digest")])
data class SyncKey(@PrimaryKey val id: String, val locale: String, val word: String, val shortcut: String?, val digest: String) {
    fun value() = ProbeValue(word, shortcut, if (locale == "ALL") null else Locale.forLanguageTag(locale).toString())
    fun matches(row: ProbeRow): Boolean = row.word == word && row.shortcut?.ifEmpty { null } == shortcut &&
        (row.locale == value().locale || from(row)?.locale == locale)
    companion object {
        // A dictionary has a handful of distinct language tags; resolving each once keeps 100,000 keys cheap.
        private val languages = java.util.concurrent.ConcurrentHashMap<String, String>()
        fun of(locale: String, word: String, shortcut: String?): SyncKey {
            val language = languages[locale] ?: me.midra.dictionarysync.data.source.SourceRules.resolvedLocale(locale).also { languages[locale] = it }
            val values = listOf(language, word, shortcut?.ifEmpty { null })
            val packed = SyncCodec.pack(values)
            return SyncKey(packed, language, word, values[2], SyncCodec.digest(packed))
        }
        fun from(row: ProbeRow): SyncKey? = try {
            // Only the standard Locale.toString()/BCP47 representations; no speculative OEM rewrites.
            of(row.locale?.replace('_', '-') ?: "ALL", row.word, row.shortcut)
        } catch (_: Exception) { null }
    }
}

@Entity(tableName = "provider_bindings")
data class ProviderBinding(@PrimaryKey val keyId: String, val installationId: String, val authority: String = "user_dictionary",
    val ownership: String, val observed: String?, val observedAt: Long, val conflict: String? = null)

@Entity(tableName = "source_references", primaryKeys = ["sourceId", "keyId"], indices = [Index("keyId")])
data class SourceReference(val sourceId: String, val snapshotId: String, val keyId: String)

@Entity(tableName = "sync_plans", indices = [Index("sourceId")])
data class SyncPlan(@PrimaryKey val id: String, val sourceId: String, val revision: Long, val oldSnapshot: String?,
    val newSnapshot: String?, val installationId: String, val fingerprint: String, val token: String,
    val approved: Boolean = false, val state: String = "PREPARED", val error: String? = null,
    val createdAt: Long, val finishedAt: Long? = null,
    val purpose: String = "APPLY",
    val parentId: String? = null, val recoveryContext: String? = null,
    val retainSource: Boolean = false)

@Entity(tableName = "plan_keys", primaryKeys = ["planId", "keyId"])
data class PlanKey(val planId: String, val keyId: String, val desired: Boolean)

@Entity(tableName = "operation_journal", indices = [Index("planId")])
data class SyncOperation(@PrimaryKey val id: String, val planId: String, val keyId: String, val kind: String,
    val phase: String = "PREPARED", val expected: String? = null, val returnedId: Long? = null, val result: Int? = null,
    val attempts: Int = 0, val error: String? = null)

/** Incremented by SQLite triggers on every change to source_references, whoever writes it. */
@Entity(tableName = "reference_generation")
data class ReferenceGeneration(@PrimaryKey val slot: Int = 1, val value: Long = 0)

@Entity(tableName = "sync_installation")
data class SyncInstallation(@PrimaryKey val slot: Int = 1, val id: String)

/** No Provider writes can occur inside a Room transaction. Specific multi-row commits are atomic. */
interface SyncStore {
    suspend fun installation(): String?
    suspend fun installation(id: String)
    suspend fun keys(): List<SyncKey>
    /** Only the given keys; the table keeps every key ever planned, so callers load what a plan involves. */
    suspend fun keys(ids: Collection<String>): List<SyncKey>
    suspend fun bindings(): List<ProviderBinding>
    /** Only the given keys' bindings, so a large plan is checked in batches without loading every binding. */
    suspend fun bindings(keyIds: Collection<String>): List<ProviderBinding>
    suspend fun ownedCount(): Int
    /** How many of [keyIds] are OWNED, without loading their bindings. */
    suspend fun ownedCount(keyIds: Collection<String>): Int
    suspend fun binding(keyId: String): ProviderBinding?
    suspend fun references(): List<SourceReference>
    suspend fun references(sourceId: String): List<SourceReference>
    /** Those of [keyIds] that a source other than [sourceId] still references. */
    suspend fun sharedKeys(sourceId: String, keyIds: Collection<String>): Set<String>
    suspend fun plans(): List<SyncPlan>
    suspend fun planKeys(id: String): List<PlanKey>
    suspend fun operations(id: String): List<SyncOperation>
    suspend fun prepare(plan: SyncPlan, keys: List<SyncKey>, planKeys: List<PlanKey>)
    suspend fun plan(plan: SyncPlan)
    suspend fun binding(binding: ProviderBinding)
    suspend fun operation(op: SyncOperation)
    suspend fun verified(binding: ProviderBinding, op: SyncOperation)
    /** Many bindings and journal entries in one transaction. */
    suspend fun record(bindings: List<ProviderBinding>, ops: List<SyncOperation>)
    suspend fun commit(plan: SyncPlan, keys: List<String>)
    suspend fun validate(plan: SyncPlan)
    suspend fun sources(): List<SyncSourceState>
    /** Changes whenever any source reference changes; pinned by recovery consent and compared at every boundary. */
    suspend fun referenceVersion(): String
    /** Atomically freezes the parent with its recovery plan; no Provider call in the transaction. */
    suspend fun prepareRecovery(plan: SyncPlan, keys: List<PlanKey>)
    suspend fun activateRecovery(plan: SyncPlan, bindings: List<ProviderBinding>)
    suspend fun finishRecovery(plan: SyncPlan)
}

data class SyncSourceState(val id: String, val revision: Long, val activeSnapshot: String?, val lifecycle: String)

interface SyncGateway {
    fun fingerprint(): String
    /** Must prove a known unchanged witness and current compatibility, even for an empty key result.
     * Returns the current Provider fingerprint it verified, so callers compare plans without a second lookup. */
    fun checkAccess(): String
    fun find(key: SyncKey): List<ProbeRow>
    fun byId(id: Long): ProbeRow?
    /** [find] for many keys, keyed by SyncKey.id. Implementations should use a few queries rather than one per key. */
    fun findAll(keys: Collection<SyncKey>): Map<String, List<ProbeRow>> = keys.associate { it.id to find(it) }
    /** [byId] for many rows; absent rows are missing from the result. */
    fun byIds(ids: Collection<Long>): Map<Long, ProbeRow> = ids.distinct().mapNotNull(::byId).associateBy { it.id }
    fun insert(value: ProbeValue): Long?
    fun delete(row: ProbeRow): Int
}
class SyncStopped(val code: String) : IllegalStateException(code)

enum class SyncReadiness { READY, IME_DISABLED, DIAGNOSIS_REQUIRED, PROBE_UNFINISHED, UNSUPPORTED }

data class SyncPreview(val plan: SyncPlan, val additions: List<SyncKey>, val removed: List<SyncKey>,
    val ownedDeletes: List<SyncKey>, val protected: Int, val shared: Int, val desiredCount: Int,
    val relatedSources: List<String> = emptyList(), val blockers: List<String> = emptyList())
