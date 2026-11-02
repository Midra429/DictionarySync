package me.midra.dictionarysync.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncRecoveryTest {
    private val a = SyncKey.of("ja", "復旧用人工語A", "よみ")
    private val b = SyncKey.of("ja", "復旧用人工語B", "よみ")
    private val c = SyncKey.of("ja", "復旧用人工語C", "べつ")
    private class Fixture {
        val store = MemorySyncStore()
        val gateway = FakeSyncGateway(store)
        fun engine() = SyncEngine(store, gateway, "installation1")
        suspend fun plan(source: String = "A", keys: List<SyncKey>, snapshot: String? = "s${store.plans.size}"): SyncPreview =
            engine().prepare(source, 1, store.refs.firstOrNull { it.sourceId == source }?.snapshotId, snapshot, "hash${store.plans.size}", keys)
        suspend fun apply(p: SyncPreview) = engine().apply(p.plan.id, p.plan.token)
        suspend fun failedCommit(keys: List<SyncKey>): SyncPreview {
            val p = plan(keys = keys); store.failCommit = true
            try { apply(p); fail() } catch (_: IllegalStateException) { }
            store.failCommit = false
            return p
        }
        suspend fun recovery(p: SyncPreview, purpose: PlanPurpose) = engine().prepareRecovery(p.plan.id, purpose)
    }
    private suspend fun stopped(code: String, block: suspend () -> Unit) {
        try { block(); fail("expected $code") } catch (e: SyncStopped) { assertEquals(code, e.code) }
    }
    @Test fun sourceRemovalAfterPartialImportPreservesUnknownManualAndSharedRows() = runBlocking {
        val f = Fixture()
        f.apply(f.plan("B", listOf(c)))
        val shared = f.gateway.rows.values.single()
        val manual = SyncKey.of("ja", "手動登録", "しゅどう").value().row(2)
        f.gateway.rows[2] = manual
        f.gateway.insertModes[3] = "NULL"
        val p = f.plan(keys = listOf(c, a, b))
        stopped("INSERT_UNKNOWN") { f.apply(p) }
        val unknown = f.gateway.rows.values.single { b.matches(it) }
        val journal = f.store.operations(p.plan.id)
        val r = f.recovery(p, PlanPurpose.REMOVE_SOURCE)
        assertEquals(0, r.desiredCount)
        assertEquals(listOf(a), r.deletes)
        f.apply(r)
        assertEquals(setOf(shared, manual, unknown), f.gateway.rows.values.toSet())
        assertEquals("B", f.store.refs.single().sourceId)
        assertEquals(journal, f.store.operations(p.plan.id))
        assertEquals(PlanState.FAILED_PRESERVED, f.store.plans.getValue(p.plan.id).state)
        assertEquals(PlanState.COMMITTED, f.store.plans.getValue(r.plan.id).state)
        assertEquals(3, f.gateway.inserts)
        f.engine().apply(r.plan.id)
        assertEquals(1, f.gateway.deletes)
        f.apply(f.plan("C", listOf(SyncKey.of("ja", "次のソース", "つぎ"))))
    }
    @Test fun sourceRemovalAfterFailedUpdateDeletesItsExistingMatches() = runBlocking {
        val f = Fixture()
        val existing = a.value().row(2).copy(frequency = 210); f.gateway.rows[2] = existing
        f.apply(f.plan(keys = listOf(a)))
        assertEquals(Ownership.BORROWED, f.store.bindings.getValue(a.id).ownership)
        val p = f.failedCommit(listOf(a, b))
        val r = f.recovery(p, PlanPurpose.REMOVE_SOURCE)
        assertEquals(setOf(a, b), r.deletes.toSet())
        f.apply(r)
        assertTrue(f.gateway.rows.isEmpty()); assertTrue(f.store.refs.isEmpty()); assertEquals(2, f.gateway.deletes)
    }
    @Test fun sourceRemovalDoesNotRestoreOldWordsDeletedByFailedUpdate() = runBlocking {
        val f = Fixture(); f.apply(f.plan(keys = listOf(a)))
        val p = f.failedCommit(listOf(b))
        f.apply(f.recovery(p, PlanPurpose.REMOVE_SOURCE))
        assertTrue(f.gateway.rows.isEmpty()); assertTrue(f.store.refs.isEmpty())
        assertEquals(2, f.gateway.inserts)
    }
    @Test fun sourceRemovalPreservesExternalEditsAndStopsWhenReadProofIsLost() = runBlocking {
        val f = Fixture(); f.apply(f.plan(keys = listOf(a)))
        val edited = f.gateway.rows.values.single().copy(word = "外部編集")
        f.gateway.rows[edited.id] = edited
        val p = f.plan(keys = listOf(a, b)); stopped("EXTERNAL_EDIT") { f.apply(p) }
        val r = f.recovery(p, PlanPurpose.REMOVE_SOURCE)
        f.gateway.access = false
        stopped("READ_UNVERIFIED") { f.apply(r) }
        assertEquals(0, f.gateway.deletes)
        f.gateway.access = true; f.apply(r)
        assertEquals(listOf(edited), f.gateway.rows.values.toList())
        assertTrue(f.store.refs.isEmpty())
    }
    @Test fun sourceRemovalResumesAtEveryPersistedStopWithoutRepeatingWrites() = runBlocking {
        for (stop in 1..35) {
            val f = Fixture(); val p = f.failedCommit(listOf(a, b))
            val r = f.recovery(p, PlanPurpose.REMOVE_SOURCE)
            f.store.events = 0; f.store.stopAt = stop
            try { f.apply(r) } catch (_: Crash) { }
            f.store.stopAt = Int.MAX_VALUE
            f.engine().apply(r.plan.id, r.plan.token)
            assertTrue("stop=$stop", f.gateway.rows.isEmpty())
            assertEquals(2, f.gateway.deletes); assertEquals(2, f.gateway.inserts)
        }
    }
    @Test fun resyncGroupsSourcesAndPreservesEditedRowWithNewOwnershipHistory() = runBlocking {
        val f = Fixture(); f.apply(f.plan(keys = listOf(a))); f.apply(f.plan("B", listOf(a, c)))
        val original = f.gateway.rows.values.single { a.matches(it) }
        val edited = original.copy(word = "手動編集後")
        f.gateway.rows[original.id] = edited
        val next = f.plan(keys = listOf(a, b)); stopped("EXTERNAL_EDIT") { f.apply(next) }
        val journal = f.store.ops.toMap()
        val recovery = f.recovery(next, PlanPurpose.RESYNC)
        assertEquals(setOf("A", "B"), recovery.relatedSources.toSet())
        stopped("APPROVAL_REQUIRED") { f.engine().apply(recovery.plan.id, next.plan.token) }
        assertEquals(2, f.gateway.inserts)
        f.apply(recovery)
        assertEquals(edited, f.gateway.rows[original.id])
        assertEquals(Ownership.OWNED, f.store.bindings.getValue(a.id).ownership)
        assertNotEquals(original.id, SyncCodec.row(f.store.bindings.getValue(a.id).observed!!).id)
        assertEquals(4, f.gateway.rows.size)
        assertEquals(PlanState.COMMITTED, f.store.plans.getValue(next.plan.id).state)
        assertTrue(f.store.refs.any { it.sourceId == "B" && it.keyId == a.id })
        assertTrue(journal.all { f.store.ops[it.key] == it.value })
        assertTrue(RecoveryContext.decode(recovery.plan.recoveryContext!!).evidence.isNotEmpty())
    }
    @Test fun frequencyOnlyEditBecomesBorrowedNeverOwnedOrDeleted() = runBlocking {
        val f = Fixture(); f.apply(f.plan(keys = listOf(a)))
        val row = f.gateway.rows.values.single().copy(frequency = 233); f.gateway.rows[row.id] = row
        val next = f.plan(keys = listOf(a)); stopped("EXTERNAL_EDIT") { f.apply(next) }
        f.apply(f.recovery(next, PlanPurpose.RESYNC))
        assertEquals(Ownership.BORROWED, f.store.bindings.getValue(a.id).ownership)
        assertEquals(1, f.gateway.inserts)
        f.apply(f.plan(keys = emptyList(), snapshot = null))
        assertEquals(listOf(row), f.gateway.rows.values.toList()); assertEquals(0, f.gateway.deletes)
    }
    @Test fun externalDeletionRequiresPinnedExplicitConsentBeforeRecreating() = runBlocking {
        val f = Fixture(); f.apply(f.plan(keys = listOf(a))); f.gateway.rows.clear()
        val next = f.plan(keys = listOf(a)); stopped("EXTERNAL_DELETE") { f.apply(next) }
        val r = f.recovery(next, PlanPurpose.RESYNC)
        stopped("APPROVAL_REQUIRED") { f.engine().apply(r.plan.id) }
        assertEquals(1, f.gateway.inserts); f.apply(r); assertEquals(2, f.gateway.inserts)
        f.engine().apply(r.plan.id); assertEquals(2, f.gateway.inserts)
    }
    @Test fun borrowedLossCanBeResolvedToAnotherExistingMatchWithoutWrites() = runBlocking {
        val f = Fixture(); f.gateway.rows[1] = a.value().row(1)
        f.apply(f.plan(keys = listOf(a))); f.gateway.rows.clear()
        val next = f.plan(keys = listOf(a)); stopped("BORROWED_LOST") { f.apply(next) }
        f.gateway.rows[2] = a.value().row(2).copy(frequency = 240)
        f.apply(f.recovery(next, PlanPurpose.RESYNC))
        assertEquals(Ownership.BORROWED, f.store.bindings.getValue(a.id).ownership)
        assertEquals(0, f.gateway.inserts); assertEquals(0, f.gateway.deletes)
    }
    @Test fun removalAfterExternalEditProtectsChangedRowWithoutRecreatingOldKey() = runBlocking {
        val f = Fixture(); f.apply(f.plan(keys = listOf(a)))
        val row = f.gateway.rows.values.single().copy(word = "編集済み"); f.gateway.rows[row.id] = row
        val next = f.plan(keys = emptyList(), snapshot = null); stopped("EXTERNAL_EDIT") { f.apply(next) }
        f.apply(f.recovery(next, PlanPurpose.RESYNC))
        assertTrue(f.store.refs.isEmpty()); assertEquals(listOf(row), f.gateway.rows.values.toList())
        assertEquals(0, f.gateway.deletes)
    }
    @Test fun changedEvidenceInvalidatesConsentAndUnstartedChildCanBeCancelled() = runBlocking {
        val f = Fixture(); f.apply(f.plan(keys = listOf(a))); f.gateway.rows.clear()
        val next = f.plan(keys = listOf(a)); stopped("EXTERNAL_DELETE") { f.apply(next) }
        val r = f.recovery(next, PlanPurpose.RESYNC)
        f.gateway.rows[2] = a.value().row(2)
        stopped("RECOVERY_STALE") { f.apply(r) }
        stopped("RECOVER_PLAN_FIRST") { f.engine().cancel(next.plan.id) }
        f.engine().cancel(r.plan.id)
        val fresh = f.recovery(next, PlanPurpose.RESYNC); assertNotEquals(r.plan.token, fresh.plan.token)
        f.apply(fresh); assertEquals(Ownership.BORROWED, f.store.bindings.getValue(a.id).ownership)
    }
    @Test fun sourceReferenceChangeInvalidatesRecoveryBeforeMutation() = runBlocking {
        val f = Fixture(); val p = f.failedCommit(listOf(a)); val r = f.recovery(p, PlanPurpose.COMPENSATE)
        f.store.refs += SourceReference("B", "other", a.id)
        stopped("RECOVERY_STALE") { f.apply(r) }; assertEquals(0, f.gateway.deletes)
    }
    @Test fun compensationAfterAddingOnlyRemovesProvenNewRows() = runBlocking {
        val f = Fixture(); f.gateway.rows[1] = c.value().row(1).copy(frequency = 244)
        f.apply(f.plan(keys = listOf(a, c)))
        val oldRefs = f.store.refs.toList(); val manual = f.gateway.rows.getValue(1)
        val p = f.failedCommit(listOf(a, b, c)); val ops = f.store.operations(p.plan.id)
        val r = f.recovery(p, PlanPurpose.COMPENSATE); f.apply(r)
        assertEquals(oldRefs, f.store.refs); assertEquals(manual, f.gateway.rows[1]); assertEquals(2, f.gateway.rows.size)
        assertEquals(PlanState.FAILED_PRESERVED, f.store.plans.getValue(p.plan.id).state)
        assertEquals(ops, f.store.operations(p.plan.id))
        assertEquals(1, f.gateway.deletes)
    }
    @Test fun compensationRestoresDeletedOldKeyBeforeRemovingNewAndCanBorrowReplacement() = runBlocking {
        for (borrow in listOf(false, true)) {
            val f = Fixture(); f.apply(f.plan(keys = listOf(a)))
            val oldId = f.gateway.rows.keys.single(); val refs = f.store.refs.toList()
            val p = f.failedCommit(listOf(b))
            assertFalse(f.gateway.rows.containsKey(oldId))
            if (borrow) f.gateway.rows[2] = a.value().row(2).copy(frequency = 215)
            val r = f.recovery(p, PlanPurpose.COMPENSATE); f.apply(r)
            assertEquals(refs, f.store.refs); assertEquals(1, f.gateway.rows.size)
            assertTrue(a.matches(f.gateway.rows.values.single()))
            assertEquals(if (borrow) Ownership.BORROWED else Ownership.OWNED, f.store.bindings.getValue(a.id).ownership)
            assertEquals(if (borrow) 2 else 3, f.gateway.inserts)
            assertEquals(PlanState.FAILED_PRESERVED, f.store.plans.getValue(p.plan.id).state)
        }
    }
    @Test fun compensationNeverRemovesSharedOrBorrowedRows() = runBlocking {
        val f = Fixture(); f.apply(f.plan("B", listOf(b)))
        val row = f.gateway.rows.values.single()
        val p = f.failedCommit(listOf(b, a))
        f.apply(f.recovery(p, PlanPurpose.COMPENSATE))
        assertEquals(listOf(row), f.gateway.rows.values.toList()); assertEquals("B", f.store.refs.single().sourceId)
    }
    @Test fun ambiguousInsertRemainsIncompleteWhileKnownAdditionIsCompensated() = runBlocking {
        val f = Fixture(); f.apply(f.plan(keys = listOf(a)))
        f.gateway.insertModes[3] = "NULL"
        val p = f.plan(keys = listOf(a, b, c)); stopped("INSERT_UNKNOWN") { f.apply(p) }
        val originalOps = f.store.operations(p.plan.id)
        val resync = f.recovery(p, PlanPurpose.RESYNC); assertTrue(resync.blockers.contains("INSERT_UNKNOWN"))
        stopped("RESYNC_BLOCKED") { f.apply(resync) }; f.engine().cancel(resync.plan.id)
        val r = f.recovery(p, PlanPurpose.COMPENSATE)
        stopped("COMPENSATION_INCOMPLETE") { f.apply(r) }
        assertEquals(2, f.gateway.rows.size); assertFalse(f.gateway.rows.values.any { b.matches(it) })
        assertTrue(f.gateway.rows.values.any { c.matches(it) }); assertEquals(3, f.gateway.inserts)
        assertEquals(originalOps, f.store.operations(p.plan.id)); assertEquals(PlanState.RECOVERY_REQUIRED, f.store.plans.getValue(r.plan.id).state)
        stopped("COMPENSATION_INCOMPLETE") { f.engine().apply(r.plan.id) }; assertEquals(1, f.gateway.deletes)
        stopped("RECOVER_PLAN_FIRST") { f.plan(keys = listOf(a)) }
    }
    @Test fun compensationProtectsExternalChangeAndDoesNotClaimFullRestoration() = runBlocking {
        val f = Fixture(); f.apply(f.plan(keys = listOf(a, c)))
        val p = f.failedCommit(listOf(b, c))
        val edited = f.gateway.rows.values.single { c.matches(it) }.copy(word = "外部編集した人工語")
        f.gateway.rows[edited.id] = edited
        val r = f.recovery(p, PlanPurpose.COMPENSATE); assertTrue(r.blockers.contains("EXTERNAL_EDIT"))
        stopped("COMPENSATION_INCOMPLETE") { f.apply(r) }
        assertEquals(edited, f.gateway.rows[edited.id]); assertTrue(f.gateway.rows.values.any { a.matches(it) })
        assertFalse(f.gateway.rows.values.any { b.matches(it) }); assertEquals(PlanState.RECOVERING, f.store.plans.getValue(p.plan.id).state)
    }
    @Test fun zeroOrMultipleDeleteCannotBeHiddenByCompensation() = runBlocking {
        for (mode in listOf("ZERO", "MULTIPLE")) {
            val f = Fixture(); f.apply(f.plan(keys = listOf(a))); val p = f.plan(keys = listOf(b))
            f.gateway.deleteMode = mode
            stopped(if (mode == "ZERO") "DELETE_ZERO" else "DELETE_MULTIPLE") { f.apply(p) }
            f.gateway.deleteMode = "NORMAL"
            val r = f.recovery(p, PlanPurpose.COMPENSATE)
            stopped("COMPENSATION_INCOMPLETE") { f.apply(r) }
            assertTrue(f.store.refs.any { it.keyId == a.id }); assertEquals(PlanState.RECOVERY_REQUIRED, f.store.plans.getValue(r.plan.id).state)
        }
    }
    @Test fun knownDeleteWithLostReturnCanBeRestoredFromJournal() = runBlocking {
        val f = Fixture(); f.apply(f.plan(keys = listOf(a)))
        val p = f.plan(keys = listOf(b)); f.gateway.deleteMode = "THROW"
        try { f.apply(p); fail() } catch (_: IllegalStateException) { }
        f.gateway.deleteMode = "NORMAL"
        f.apply(f.recovery(p, PlanPurpose.COMPENSATE))
        assertEquals(1, f.gateway.rows.size); assertTrue(a.matches(f.gateway.rows.values.single()))
    }
    @Test fun lostReadProofStopsRecoveryWithoutProviderMutation() = runBlocking {
        val f = Fixture(); val p = f.failedCommit(listOf(a)); f.gateway.access = false
        stopped("READ_UNVERIFIED") { f.recovery(p, PlanPurpose.COMPENSATE) }; assertEquals(0, f.gateway.deletes)
        f.gateway.access = true; val r = f.recovery(p, PlanPurpose.COMPENSATE); f.gateway.access = false
        stopped("READ_UNVERIFIED") { f.apply(r) }; assertEquals(0, f.gateway.deletes)
    }
    @Test fun compensationStopPointsNeverBlindlyRepeatInsertOrDeleteManualRows() = runBlocking {
        for (stop in 1..55) {
            val f = Fixture(); val manual = c.value().row(2).copy(frequency = 234); f.gateway.rows[2] = manual
            f.apply(f.plan(keys = listOf(a, c))); val p = f.failedCommit(listOf(b, c))
            val r = f.recovery(p, PlanPurpose.COMPENSATE)
            f.store.events = 0; f.store.stopAt = stop
            try { f.apply(r) } catch (_: Crash) { }
            f.store.stopAt = Int.MAX_VALUE
            try { f.engine().apply(r.plan.id, r.plan.token) } catch (e: SyncStopped) { assertEquals("INSERT_UNKNOWN", e.code) }
            assertEquals("stop=$stop", manual, f.gateway.rows[2]); assertTrue(f.gateway.inserts <= 3)
            if (f.store.plans.getValue(r.plan.id).state == PlanState.COMMITTED) {
                assertEquals(setOf(a.id, c.id), f.gateway.rows.values.map { SyncKey.from(it)!!.id }.toSet())
                assertEquals(PlanState.FAILED_PRESERVED, f.store.plans.getValue(p.plan.id).state)
            } else assertEquals(PlanState.RECOVERY_REQUIRED, f.store.plans.getValue(r.plan.id).state)
        }
    }
    @Test fun resyncStopPointsRetainEditedRowsAndUseDurableApproval() = runBlocking {
        for (stop in 1..45) {
            val f = Fixture(); f.apply(f.plan(keys = listOf(a)))
            val edited = f.gateway.rows.values.single().copy(word = "保護する人工語"); f.gateway.rows[edited.id] = edited
            val p = f.plan(keys = listOf(a)); stopped("EXTERNAL_EDIT") { f.apply(p) }
            val r = f.recovery(p, PlanPurpose.RESYNC); f.store.events = 0; f.store.stopAt = stop
            try { f.apply(r) } catch (_: Crash) { }
            f.store.stopAt = Int.MAX_VALUE
            try { f.engine().apply(r.plan.id, r.plan.token) } catch (e: SyncStopped) { assertEquals("INSERT_UNKNOWN", e.code) }
            assertEquals(edited, f.gateway.rows[edited.id]); assertTrue(f.gateway.inserts <= 2); assertEquals(0, f.gateway.deletes)
        }
    }
    @Test fun missingBindingCannotBeHiddenByCompensationOrInferredFromContents() = runBlocking {
        val f = Fixture(); val p = f.failedCommit(listOf(a, b)); f.store.bindings.remove(a.id)
        val r = f.recovery(p, PlanPurpose.COMPENSATE); assertTrue(r.blockers.contains("BINDING_MISSING"))
        stopped("COMPENSATION_INCOMPLETE") { f.apply(r) }
        assertTrue(f.gateway.rows.values.any { a.matches(it) }); assertFalse(f.gateway.rows.values.any { b.matches(it) })
        assertNull(f.store.bindings[a.id]); assertEquals(PlanState.RECOVERY_REQUIRED, f.store.plans.getValue(r.plan.id).state)
    }
    @Test fun recoveryPreparationStopPointsRetainParentAndNeverWriteProvider() = runBlocking {
        for (stop in 1..14) {
            val f = Fixture(); f.apply(f.plan(keys = listOf(a))); val p = f.failedCommit(listOf(b))
            val rows = f.gateway.rows.toMap(); val writes = f.gateway.inserts + f.gateway.deletes
            f.store.events = 0; f.store.stopAt = stop
            try { f.recovery(p, PlanPurpose.COMPENSATE) } catch (_: Crash) { }
            f.store.stopAt = Int.MAX_VALUE
            assertEquals(rows, f.gateway.rows); assertEquals(writes, f.gateway.inserts + f.gateway.deletes)
            val child = f.store.plans.values.find { it.parentId == p.plan.id }
            val r = if (child == null) f.recovery(p, PlanPurpose.COMPENSATE) else f.engine().preview(child)
            f.apply(r)
            assertEquals(1, f.gateway.rows.size); assertTrue(a.matches(f.gateway.rows.values.single()))
        }
    }
}
