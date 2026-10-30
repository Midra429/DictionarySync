package me.midra.dictionarysync.probe

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant

/** Public Provider operations. All mutation predicates contain an ID AND every observed column. */
interface ProbeGateway {
    fun snapshot(): List<ProbeRow>
    fun insert(value: ProbeValue): Long?
    fun update(id: Long, expected: ProbeRow, frequency: Int): Int
    fun delete(id: Long, expected: ProbeRow): Int
    fun notificationCount(): Int
}

data class ProbeValue(val word: String, val shortcut: String?, val locale: String?, val frequency: Int = 128) {
    fun row(id: Long) = ProbeRow(id, word, shortcut, locale, frequency, 0)
}

data class ProbeRecord(
    val value: ProbeValue,
    val id: Long? = null,
    val attempted: Boolean = false,
    val confirmed: ProbeRow? = null,
    val updateTarget: ProbeRow? = null,
    val removed: Boolean = false,
)

data class ProbeSession(
    val kind: String,
    val phase: String,
    val marker: String,
    val witness: ProbeRow,
    val baselineHash: String,
    val baselineCount: Int,
    val records: List<ProbeRecord>,
    val events: List<String> = emptyList(),
    val unsafePredicate: Boolean = false,
    val basicPassed: Boolean = false,
    val finished: Boolean = false,
)

interface ProbeJournal {
    fun load(): ProbeSession?
    fun lastBasic(): ProbeSession?
    fun save(session: ProbeSession)
}

class ProbeStopped(val reason: String) : IllegalStateException(reason)

/** Checks conditional CRUD support. Interrupted trials are reconciled without repeating inserts. */
class WriteProbe(private val gateway: ProbeGateway, private val journal: ProbeJournal) {
    fun basic(marker: String): ProbeSession = synchronized(lock) {
        begin("BASIC", marker, listOf(ProbeValue("${ReadProbe.TRIAL_PREFIX}${marker.takeLast(8)}", null, null)))
        try {
            insertNext(0)
            val original = current().records.single().confirmed!!
            guard()
            event("NEGATIVE_UPDATE_IN_FLIGHT")
            val updated = gateway.update(original.id, original.copy(word = original.word + "_mismatch"), 129)
            event("negative update count=$updated")
            if (updated != 0 || gateway.snapshot().find { it.id == original.id } != original) unsafe("Negative update ignored its condition")
            guard()
            event("NEGATIVE_DELETE_IN_FLIGHT")
            val deleted = gateway.delete(original.id, original.copy(word = original.word + "_mismatch"))
            event("negative delete count=$deleted")
            if (deleted != 0 || gateway.snapshot().find { it.id == original.id } != original) unsafe("Negative delete ignored its condition")
            guard()
            val target = original.copy(frequency = 129)
            replace(0) { it.copy(updateTarget = target) }
            event("MATCHING_UPDATE_IN_FLIGHT")
            val count = gateway.update(original.id, original, 129)
            event("matching update count=$count")
            if (count > 1) unsafe("Matching update affected multiple rows")
            if (count != 1) stop("Matching update did not return 1")
            if (gateway.snapshot().find { it.id == original.id } != target) stop("Update readback differs")
            replace(0) { it.copy(confirmed = target, updateTarget = null) }
            event("UPDATE_VERIFIED")
            deleteRecord(0)
            finish(basicPassed = true)
        } catch (error: Exception) {
            recordFailure(error)
            throw error
        }
    }

    /** Recovery only cleans proven IDs with unchanged values. Unknown insert results remain manual. */
    fun cleanup(): ProbeSession = synchronized(lock) {
        val initial = current()
        if (initial.finished) return@synchronized initial
        if (initial.unsafePredicate) stop("Predicate is unsafe; use Settings to remove only the test words")
        try {
            // Reconcile every missing ID before deleting any row. A later record may have been
            // removed externally; strict per-delete guards must see that reconciled state.
            val preflight = guard(allowMissing = true)
            initial.records.indices.forEach { index ->
                val record = current().records[index]
                if (record.removed) return@forEach
                if (!record.attempted) {
                    replace(index) { it.copy(removed = true) }
                    return@forEach
                }
                if (record.id == null) {
                    // No URI/ID returned (or process died before it was saved). Never infer ownership.
                    stop("An insert result is unknown; inspect the exact test word in Settings")
                }
                val actual = preflight.find { it.id == record.id }
                if (actual == null) {
                    replace(index) { it.copy(removed = true) }
                    event("cleanup: recorded ID ${record.id} already absent")
                } else {
                    val expected = record.confirmed ?: record.value.row(record.id)
                    if (actual != expected && actual != record.updateTarget) stop("External change: protect this row")
                    replace(index) { it.copy(confirmed = actual, updateTarget = null) }
                }
            }
            initial.records.indices.forEach { index ->
                if (!current().records[index].removed) deleteRecord(index)
            }
            finish(basicPassed = false)
        } catch (error: Exception) {
            recordFailure(error)
            throw error
        }
    }

    /** A read-only inspection can be used after external Settings edits; it never reacquires ownership. */
    fun inspect(): String = synchronized(lock) {
        val session = current()
        val rows = gateway.snapshot()
        if (rows.singleOrNull { it.word == session.marker } != session.witness) stop("Reading witness is not visible or changed")
        val report = buildString {
            appendLine("inspection ${Instant.now()}")
            appendLine("notifications=${gateway.notificationCount()}")
            session.records.forEach { record ->
                val actual = rows.find { it.id == record.id }
                appendLine("${record.value.word}: " + when {
                    record.id == null -> "UNKNOWN_ID; manual review required"
                    actual == null -> "ID_ABSENT"
                    actual == record.confirmed -> "UNCHANGED"
                    else -> "EXTERNAL_CHANGE_PROTECTED"
                })
                rows.filter { it.word == record.value.word + "_edited" }.forEach {
                    appendLine("Settings-edited synthetic row: id=${it.id}; protected, never adopted")
                }
            }
            append("Other rows match baseline: ${fingerprint(rows.filter { r -> session.records.none { it.id == r.id } }) == session.baselineHash}")
        }
        event(report)
        report
    }

    private fun begin(kind: String, marker: String, values: List<ProbeValue>) {
        require(ReadProbe.MARKER_PATTERN.matches(marker))
        if (journal.load()?.finished == false) stop("An unfinished trial must be reconciled first")
        val rows = gateway.snapshot()
        val witness = rows.singleOrNull { it.word == marker } ?: stop("Read the known manual marker first")
        if (rows.map { it.id }.toSet().size != rows.size) stop("Duplicate IDs")
        if (rows.any { row -> values.any { it.word == row.word } }) stop("A test word already exists; do not adopt it")
        journal.save(ProbeSession(kind, "PREPARED", marker, witness, fingerprint(rows), rows.size,
            values.map { ProbeRecord(it) }))
        event("PREPARED $kind; baseline count=${rows.size}")
    }

    private fun insertNext(index: Int) {
        val before = guard()
        val record = current().records[index]
        if (record.id != null) stop("Never repeat a recorded insert")
        replace(index) { it.copy(attempted = true) }
        event("INSERT_IN_FLIGHT index=$index")
        val id = gateway.insert(record.value) ?: stop("Insert returned null; result unknown")
        // Persist the returned ID before any readback or other operation.
        replace(index) { it.copy(id = id) }
        event("INSERT_RETURNED id=$id")
        if (id <= 0 || before.any { it.id == id }) stop("Insert returned an existing or invalid ID")
        val after = gateway.snapshot()
        val actual = after.singleOrNull { it.id == id }
        if (actual != record.value.row(id)) stop("Insert readback differs; do not claim ownership")
        replace(index) { it.copy(confirmed = actual) }
        guard()
        event("INSERT_VERIFIED id=$id, appid=${actual.appId}, locale=${actual.locale}, shortcut=${actual.shortcut}, notifications=${gateway.notificationCount()}")
    }

    private fun deleteRecord(index: Int) {
        val rows = guard()
        val record = current().records[index]
        val expected = record.confirmed ?: stop("No verified ownership")
        if (rows.find { it.id == record.id } != expected) stop("External change: protect this row")
        event("DELETE_IN_FLIGHT id=${expected.id}")
        val count = gateway.delete(expected.id, expected)
        event("matching delete count=$count")
        if (count > 1) unsafe("Matching delete affected multiple rows")
        if (count != 1) stop("Matching delete did not return 1")
        val after = gateway.snapshot()
        if (after.singleOrNull { it.word == current().marker } != current().witness) stop("Read access lost after deletion")
        if (after.any { it.id == expected.id }) stop("Deleted row still present")
        replace(index) { it.copy(removed = true) }
        guard()
        event("DELETE_VERIFIED id=${expected.id}, notifications=${gateway.notificationCount()}")
    }

    private fun guard(allowMissing: Boolean = false): List<ProbeRow> {
        val session = current()
        val rows = gateway.snapshot()
        if (rows.singleOrNull { it.word == session.marker } != session.witness) stop("Read witness lost or edited")
        val ownedIds = session.records.mapNotNull { it.id }.toSet()
        if (fingerprint(rows.filter { it.id !in ownedIds }) != session.baselineHash) stop("Unmanaged rows changed; stop")
        session.records.filter { it.id != null && !it.removed && it.confirmed != null }.forEach { record ->
            val actual = rows.singleOrNull { it.id == record.id }
            if (actual == null && allowMissing) return@forEach
            if (actual != record.confirmed && actual != record.updateTarget) stop("Managed test row changed; protect it")
        }
        return rows
    }

    private fun finish(basicPassed: Boolean): ProbeSession {
        val rows = gateway.snapshot()
        if (rows.singleOrNull { it.word == current().marker } != current().witness) stop("Final read witness missing")
        if (fingerprint(rows) != current().baselineHash) stop("Final dictionary differs from baseline")
        val result = current().copy(phase = "FINISHED", finished = true, basicPassed = basicPassed,
            events = current().events + "${Instant.now()} FINISHED; baseline restored; basicPassed=$basicPassed")
        journal.save(result)
        return result
    }

    private fun current() = journal.load() ?: stop("No trial")
    private fun replace(index: Int, transform: (ProbeRecord) -> ProbeRecord) {
        val session = current()
        journal.save(session.copy(records = session.records.mapIndexed { i, row -> if (i == index) transform(row) else row }))
    }
    private fun event(message: String) {
        val session = current()
        journal.save(session.copy(phase = message, events = session.events + "${Instant.now()} $message"))
    }
    private fun unsafe(reason: String): Nothing {
        journal.save(current().copy(unsafePredicate = true))
        stop(reason)
    }
    private fun recordFailure(error: Exception) {
        // Only our own fixed diagnostics are safe; Provider exception messages can expose personal data.
        val reason = (error as? ProbeStopped)?.reason ?: error.javaClass.simpleName
        event("STOPPED: $reason")
    }
    private fun stop(reason: String): Nothing = throw ProbeStopped(reason)

    companion object {
        private val lock = Any()

        fun fingerprint(rows: List<ProbeRow>): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                fun field(value: String?) {
                    if (value == null) out.writeInt(-1) else {
                        val data = value.toByteArray(Charsets.UTF_8)
                        out.writeInt(data.size); out.write(data)
                    }
                }
                rows.sortedBy { it.id }.forEach { row ->
                    out.writeLong(row.id)
                    listOf(row.word, row.shortcut, row.locale, row.frequency?.toString(), row.appId?.toString()).forEach(::field)
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
        }

    }
}
