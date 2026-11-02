package me.midra.dictionarysync.sync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.UserDictionary.Words
import android.util.AtomicFile
import android.view.inputmethod.InputMethodManager
import java.io.File
import me.midra.dictionarysync.probe.*

/** Current diagnostic certificate + unchanged manual witness. No privileged or shell Provider access. */
class AndroidSyncGateway(private val context: Context) : SyncGateway {
    private val resolver = context.contentResolver
    private val certificate = AtomicFile(File(context.noBackupFilesDir, "sync-capability"))
    @Suppress("DEPRECATION")
    override fun fingerprint(): String {
        val provider = context.packageManager.resolveContentProvider("user_dictionary", 0) ?: throw SyncStopped("PROVIDER_MISSING")
        val info = context.packageManager.getPackageInfo(provider.packageName, 0)
        return SyncCodec.hash(listOf(Build.FINGERPRINT, Build.VERSION.SECURITY_PATCH, provider.packageName,
            (if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()).toString(), info.lastUpdateTime.toString()))
    }
    /** Called only after a NEW basic CRUD succeeds in this installation/environment. */
    fun certify() {
        val trial = AndroidProbeJournal(context).lastBasic() ?: throw SyncStopped("DIAGNOSIS_REQUIRED")
        if (!trial.finished || !trial.basicPassed || trial.unsafePredicate) throw SyncStopped("DIAGNOSIS_REQUIRED")
        val stream = certificate.startWrite()
        try { stream.write(fingerprint().toByteArray()); certificate.finishWrite(stream) }
        catch (error: Exception) { certificate.failWrite(stream); throw error }
    }
    /** Read-only summary for the UI; [checkAccess] (including the witness row) still gates every write. */
    fun readiness(): SyncReadiness {
        if (!context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == context.packageName }) return SyncReadiness.IME_DISABLED
        if (AndroidProbeJournal(context).load()?.finished == false) return SyncReadiness.PROBE_UNFINISHED
        val recorded = try { certificate.openRead().bufferedReader().use { it.readText() } } catch (_: java.io.FileNotFoundException) { return SyncReadiness.DIAGNOSIS_REQUIRED }
        val current = try { fingerprint() } catch (_: SyncStopped) { return SyncReadiness.UNSUPPORTED }
        return if (recorded == current) SyncReadiness.READY else SyncReadiness.DIAGNOSIS_REQUIRED
    }
    /**
     * Checked before every Provider step. The certificate and probe journal are written only by diagnostics under the same
     * SyncCoordinator lock that a sync holds for its whole operation, so they are read once per gateway (one operation).
     * The keyboard, Provider fingerprint and witness row can change at any time and are checked on every call.
     */
    override fun checkAccess(): String {
        if (!context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == context.packageName }) throw SyncStopped("IME_DISABLED")
        val diagnosis = diagnosis ?: readDiagnosis().also { diagnosis = it }
        if (diagnosis.certificate == null) throw SyncStopped("DIAGNOSIS_REQUIRED")
        val current = fingerprint() // Once per check, for both the certificate and the plan.
        if (diagnosis.certificate != current) throw SyncStopped("DIAGNOSIS_REQUIRED")
        if (diagnosis.unfinished) throw SyncStopped("PROBE_UNFINISHED")
        val witness = diagnosis.witness ?: throw SyncStopped("DIAGNOSIS_REQUIRED")
        if (byId(witness.id) != witness) throw SyncStopped("READ_WITNESS_LOST")
        return current
    }
    override fun reserved(row: ProbeRow) = (diagnosis ?: readDiagnosis().also { diagnosis = it }).witness?.id == row.id
    private class Diagnosis(val certificate: String?, val unfinished: Boolean, val witness: ProbeRow?)
    private var diagnosis: Diagnosis? = null
    private fun readDiagnosis(): Diagnosis {
        val recorded = try { certificate.openRead().bufferedReader().use { it.readText() } } catch (_: java.io.FileNotFoundException) { null }
        val journal = AndroidProbeJournal(context)
        return Diagnosis(recorded, journal.load()?.finished == false, journal.lastBasic()?.takeIf { it.basicPassed && it.finished }?.witness)
    }
    private fun query(id: Long? = null, word: String? = null): List<ProbeRow> {
        val uri = if (id == null) Words.CONTENT_URI else ContentUris.withAppendedId(Words.CONTENT_URI, id)
        return if (id != null) query(uri, "_id = ?", listOf(id.toString())) { it.id == id }
        else query(uri, "word = ?", listOf(requireNotNull(word))) { it.word == word }
    }
    /** [accepts] proves the Provider applied the selection; a row outside it means the predicate was ignored. */
    private fun query(uri: android.net.Uri, selection: String, args: List<String>, accepts: (ProbeRow) -> Boolean): List<ProbeRow> {
        val cursor = resolver.query(uri, AndroidDictionaryReader.COLUMNS, selection, args.toTypedArray(), null) ?: throw SyncStopped("NULL_CURSOR")
        return cursor.use { c ->
            val indices = AndroidDictionaryReader.COLUMNS.map { c.getColumnIndexOrThrow(it) }
            buildList {
                while (c.moveToNext()) {
                    if (size >= 100_000) throw SyncStopped("QUERY_LIMIT")
                    fun text(i: Int) = if (c.isNull(indices[i])) null else c.getString(indices[i])
                    fun number(i: Int) = if (c.isNull(indices[i])) null else c.getInt(indices[i])
                    val row = ProbeRow(c.getLong(indices[0]), text(1) ?: throw SyncStopped("MISSING_WORD"), text(2), text(3), number(4), number(5))
                    if (!accepts(row)) throw SyncStopped("QUERY_PREDICATE_IGNORED")
                    add(row)
                }
            }
        }
    }
    /** One IN query per chunk, below SQLite's 999 bound-variable limit on older Android versions. */
    private fun <T> queryIn(column: String, values: Collection<T>, accepts: (ProbeRow, Set<String>) -> Boolean): List<ProbeRow> =
        values.map { it.toString() }.distinct().chunked(900).flatMap { chunk ->
            val set = chunk.toSet()
            query(Words.CONTENT_URI, "$column IN (${chunk.joinToString(",") { "?" }})", chunk) { accepts(it, set) }
        }
    override fun byId(id: Long): ProbeRow? = query(id = id).let {
        if (it.size > 1) throw SyncStopped("DUPLICATE_ID")
        it.singleOrNull()
    }
    override fun byIds(ids: Collection<Long>): Map<Long, ProbeRow> {
        val rows = queryIn("_id", ids) { row, set -> row.id.toString() in set }
        return rows.associateBy { it.id }.also { if (it.size != rows.size) throw SyncStopped("DUPLICATE_ID") }
    }
    override fun find(key: SyncKey) = query(word = key.word).filter { key.matches(it) }
    override fun findAll(keys: Collection<SyncKey>): Map<String, List<ProbeRow>> {
        val rows = queryIn("word", keys.map { it.word }) { row, set -> row.word in set }.groupBy { it.word }
        return keys.associate { key -> key.id to rows[key.word].orEmpty().filter { key.matches(it) } }
    }
    override fun insert(value: ProbeValue): Long? {
        val uri = resolver.insert(Words.CONTENT_URI, ContentValues().apply {
            put(Words.WORD, value.word); put(Words.SHORTCUT, value.shortcut); put(Words.LOCALE, value.locale)
            put(Words.FREQUENCY, value.frequency); put(Words.APP_ID, 0)
        }) ?: return null
        if (uri.scheme != "content" || uri.authority != "user_dictionary" || uri.pathSegments.size != 2 ||
            uri.pathSegments.first() != "words" || uri.query != null || uri.fragment != null) throw SyncStopped("INSERT_URI_UNKNOWN")
        return uri.lastPathSegment?.toLongOrNull() ?: throw SyncStopped("INSERT_ID_UNKNOWN")
    }
    override fun delete(row: ProbeRow): Int {
        val predicate = exactPredicate(row)
        return resolver.delete(ContentUris.withAppendedId(Words.CONTENT_URI, row.id), predicate.sql, predicate.args.toTypedArray())
    }
}
