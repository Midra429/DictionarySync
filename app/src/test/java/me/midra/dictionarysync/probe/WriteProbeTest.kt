package me.midra.dictionarysync.probe

import org.junit.Assert.*
import org.junit.Test

class WriteProbeTest {
    private val marker = "dictionarysync_check_a1b2c3d4"
    private val witness = ProbeRow(1, marker, null, "ja", 250, 0)
    private val personal = ProbeRow(2, "手動の語", "てどう", "ja", 231, 0)

    private class MemoryJournal : ProbeJournal {
        var active: ProbeSession? = null
        var basic: ProbeSession? = null
        var crashAfterId = false
        override fun load() = active
        override fun lastBasic() = basic
        override fun save(session: ProbeSession) {
            active = session
            if (session.finished && session.kind == "BASIC") basic = session
            if (crashAfterId && session.records.any { it.id != null }) {
                crashAfterId = false
                throw SimulatedProcessDeath()
            }
        }
    }
    private class SimulatedProcessDeath : Error()
    private class Fake(initial: List<ProbeRow>) : ProbeGateway {
        val rows = initial.toMutableList()
        var nextId = 100L
        var insertCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var emptyReads = false
        var ignoreUpdate = false
        var ignoreDelete = false
        var denyMatchingUpdate = false
        var dieAfterInsert = false
        var emptyAfterDelete = false
        override fun snapshot() = if (emptyReads) emptyList() else rows.toList()
        override fun notificationCount() = 0
        override fun insert(value: ProbeValue): Long {
            insertCalls++
            val id = nextId++
            rows += value.row(id)
            if (dieAfterInsert) throw SimulatedProcessDeath()
            return id
        }
        override fun update(id: Long, expected: ProbeRow, frequency: Int): Int {
            updateCalls++
            if (denyMatchingUpdate) return 0
            val index = rows.indexOfFirst { it.id == id && (ignoreUpdate || it == expected) }
            if (index < 0) return 0
            rows[index] = rows[index].copy(frequency = frequency)
            return 1
        }
        override fun delete(id: Long, expected: ProbeRow): Int {
            deleteCalls++
            val removed = rows.removeAll { it.id == id && (ignoreDelete || it == expected) }
            if (removed && emptyAfterDelete) emptyReads = true
            return if (removed) 1 else 0
        }
    }

    @Test fun safeBasicCrudRestoresAllExistingColumns() {
        val fake = Fake(listOf(witness, personal))
        val journal = MemoryJournal()
        val result = WriteProbe(fake, journal).basic(marker)
        assertTrue(result.basicPassed)
        assertTrue(result.finished)
        assertEquals(listOf(witness, personal), fake.rows)
        assertEquals(1, fake.insertCalls)
        assertEquals(2, fake.updateCalls)
        assertEquals(2, fake.deleteCalls)
    }

    @Test fun emptyCursorBlocksAllWrites() {
        val fake = Fake(listOf(witness)).apply { emptyReads = true }
        assertThrows(ProbeStopped::class.java) { WriteProbe(fake, MemoryJournal()).basic(marker) }
        assertEquals(0, fake.insertCalls)
    }

    @Test fun sameContentPreexistingRowIsNeverAdopted() {
        val existing = ProbeValue("dictionarysync_test_a1b2c3d4", null, null).row(3)
        val fake = Fake(listOf(witness, existing))
        assertThrows(ProbeStopped::class.java) { WriteProbe(fake, MemoryJournal()).basic(marker) }
        assertEquals(0, fake.insertCalls)
        assertEquals(0, fake.deleteCalls)
    }

    @Test fun negativeUpdateDetectsIgnoredPredicateAndRequiresManualCleanup() {
        val fake = Fake(listOf(witness, personal)).apply { ignoreUpdate = true }
        val journal = MemoryJournal()
        val probe = WriteProbe(fake, journal)
        assertThrows(ProbeStopped::class.java) { probe.basic(marker) }
        assertTrue(journal.active!!.unsafePredicate)
        assertThrows(ProbeStopped::class.java) { probe.cleanup() }
        assertEquals(0, fake.deleteCalls)
        assertEquals(personal, fake.rows.find { it.id == 2L })
    }

    @Test fun negativeDeleteDetectsIgnoredPredicate() {
        val fake = Fake(listOf(witness, personal)).apply { ignoreDelete = true }
        val journal = MemoryJournal()
        assertThrows(ProbeStopped::class.java) { WriteProbe(fake, journal).basic(marker) }
        assertTrue(journal.active!!.unsafePredicate)
        assertFalse(journal.active!!.basicPassed)
        assertEquals(listOf(witness, personal), fake.rows)
    }

    @Test fun matchingUpdateZeroIsNotSuccess() {
        val fake = Fake(listOf(witness)).apply { denyMatchingUpdate = true }
        val journal = MemoryJournal()
        assertThrows(ProbeStopped::class.java) { WriteProbe(fake, journal).basic(marker) }
        assertFalse(journal.active!!.basicPassed)
        assertFalse(journal.active!!.finished)
    }

    @Test fun crashBeforeReturnedIdNeverAdoptsByContentOrRepeatsInsert() {
        val fake = Fake(listOf(witness)).apply { dieAfterInsert = true }
        val journal = MemoryJournal()
        assertThrows(SimulatedProcessDeath::class.java) { WriteProbe(fake, journal).basic(marker) }
        assertNull(journal.active!!.records.single().id)
        assertThrows(ProbeStopped::class.java) { WriteProbe(fake, journal).basic(marker) }
        assertThrows(ProbeStopped::class.java) { WriteProbe(fake, journal).cleanup() }
        assertEquals(1, fake.insertCalls)
        assertEquals(0, fake.deleteCalls)
    }

    @Test fun crashAfterReturnedIdCanReconcileAndCleanKnownUnchangedRow() {
        val fake = Fake(listOf(witness, personal))
        val journal = MemoryJournal().apply { crashAfterId = true }
        assertThrows(SimulatedProcessDeath::class.java) { WriteProbe(fake, journal).basic(marker) }
        val result = WriteProbe(fake, journal).cleanup()
        assertTrue(result.finished)
        assertFalse(result.basicPassed) // Cleaning up does not pass an interrupted test.
        assertEquals(listOf(witness, personal), fake.rows)
    }

    @Test fun readAccessLossAfterDeletionDoesNotPass() {
        val fake = Fake(listOf(witness)).apply { emptyAfterDelete = true }
        val journal = MemoryJournal()
        assertThrows(ProbeStopped::class.java) { WriteProbe(fake, journal).basic(marker) }
        assertFalse(journal.active!!.finished)
    }

    @Test fun editedReturnedIdIsProtectedAfterCrash() {
        val fake = Fake(listOf(witness))
        val journal = MemoryJournal().apply { crashAfterId = true }
        assertThrows(SimulatedProcessDeath::class.java) { WriteProbe(fake, journal).basic(marker) }
        fake.rows[1] = fake.rows[1].copy(word = "外部の編集")
        assertThrows(ProbeStopped::class.java) { WriteProbe(fake, journal).cleanup() }
        assertEquals(0, fake.deleteCalls)
    }

    @Test fun unrelatedExternalChangeStopsCleanupWithoutRevertingIt() {
        val fake = Fake(listOf(witness, personal))
        val journal = MemoryJournal().apply { crashAfterId = true }
        assertThrows(SimulatedProcessDeath::class.java) { WriteProbe(fake, journal).basic(marker) }
        fake.rows[1] = personal.copy(frequency = 222)
        assertThrows(ProbeStopped::class.java) { WriteProbe(fake, journal).cleanup() }
        assertEquals(0, fake.deleteCalls)
        assertEquals(222, fake.rows[1].frequency)
    }

    // Old releases could leave a CANDIDATES journal. Keep recovery coverage without
    // retaining the removed experiment's insertion path in the production app.
    private fun legacyCandidates(fake: Fake, journal: MemoryJournal): ProbeSession {
        val baseline = fake.rows.toList()
        val records = (1..13).map { index ->
            val value = ProbeValue("legacy_candidate_$index", "よみ$index", "ja")
            val row = value.row(fake.nextId++)
            fake.rows += row
            ProbeRecord(value, row.id, attempted = true, confirmed = row)
        }
        return ProbeSession("CANDIDATES", "CANDIDATES_READY", marker, witness,
            WriteProbe.fingerprint(baseline), baseline.size, records).also(journal::save)
    }

    @Test fun legacyCandidatesCanBeCompletelyCleaned() {
        val fake = Fake(listOf(witness, personal))
        val journal = MemoryJournal()
        val probe = WriteProbe(fake, journal)
        val session = legacyCandidates(fake, journal)
        assertEquals(13, session.records.size)
        assertEquals(15, fake.rows.size)
        assertTrue(session.records.all { it.confirmed != null })
        assertThrows(ProbeStopped::class.java) { probe.basic(marker) }
        assertEquals(0, fake.insertCalls)
        assertTrue(probe.cleanup().finished)
        assertEquals(listOf(witness, personal), fake.rows)
    }

    @Test fun nullAndEmptyPredicatesAreDifferentAndWordIsParameterized() {
        val row = witness.copy(word = "a' OR 1=1", locale = null)
        val predicate = exactPredicate(row)
        assertTrue(predicate.sql.contains("shortcut IS NULL"))
        assertTrue(predicate.sql.contains("locale IS NULL"))
        assertFalse(predicate.sql.contains(row.word))
        assertTrue(predicate.args.contains(row.word))
        val empty = exactPredicate(row.copy(shortcut = ""))
        assertTrue(empty.sql.contains("shortcut = ?"))
        assertTrue(empty.args.contains(""))
    }

    @Test fun externallyDeletedMiddleCandidateIsReconciledBeforeCleanupWrites() {
        val fake = Fake(listOf(witness, personal))
        val journal = MemoryJournal()
        val session = legacyCandidates(fake, journal)
        val removedId = session.records[5].id
        fake.rows.removeAll { it.id == removedId }
        assertTrue(WriteProbe(fake, journal).cleanup().finished)
        assertEquals(listOf(witness, personal), fake.rows)
    }

    @Test fun changedLaterCandidateStopsCleanupBeforeAnyDeletion() {
        val fake = Fake(listOf(witness, personal))
        val journal = MemoryJournal()
        val probe = WriteProbe(fake, journal)
        val session = legacyCandidates(fake, journal)
        val index = fake.rows.indexOfFirst { it.id == session.records[5].id }
        fake.rows[index] = fake.rows[index].copy(word = "外部編集")
        val deletesBefore = fake.deleteCalls
        assertThrows(ProbeStopped::class.java) { probe.cleanup() }
        assertEquals(deletesBefore, fake.deleteCalls)
    }

    @Test fun fingerprintsAreOrderIndependentButNullAndEmptyAreDistinct() {
        assertEquals(WriteProbe.fingerprint(listOf(witness, personal)), WriteProbe.fingerprint(listOf(personal, witness)))
        assertNotEquals(WriteProbe.fingerprint(listOf(witness)), WriteProbe.fingerprint(listOf(witness.copy(shortcut = ""))))
    }
}
