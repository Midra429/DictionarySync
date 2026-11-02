package me.midra.dictionarysync.sync

import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.SourceLifecycle
import me.midra.dictionarysync.probe.*
import org.junit.Assert.*
import org.junit.Test

/** In-memory store simulates committed Room writes; Crash is process death, not a handled exception. */
class MemorySyncStore : SyncStore {
    var installation: String? = null
    val keys = linkedMapOf<String, SyncKey>()
    val bindings = linkedMapOf<String, ProviderBinding>()
    val refs = mutableListOf<SourceReference>()
    val plans = linkedMapOf<String, SyncPlan>()
    val planKeys = mutableListOf<PlanKey>()
    val ops = linkedMapOf<String, SyncOperation>()
    var stopAt = Int.MAX_VALUE
    var events = 0
    var stale = false
    var failCommit = false
    fun event() { if (++events == stopAt) throw Crash() }
    override suspend fun installation() = installation
    override suspend fun installation(id: String) { installation = id; event() }
    var fullKeyLoads = 0
    override suspend fun keys() = keys.values.toList().also { fullKeyLoads++ }
    override suspend fun keys(ids: Collection<String>) = ids.distinct().mapNotNull { keys[it] }
    override suspend fun bindings() = bindings.values.toList()
    override suspend fun bindings(keyIds: Collection<String>) = keyIds.distinct().mapNotNull { bindings[it] }
    override suspend fun ownedCount() = bindings.values.count { it.ownership == Ownership.OWNED }
    override suspend fun ownedCount(keyIds: Collection<String>) = keyIds.distinct().count { bindings[it]?.ownership == Ownership.OWNED }
    override suspend fun binding(keyId: String) = bindings[keyId]
    override suspend fun references() = refs.toList()
    override suspend fun references(sourceId: String) = refs.filter { it.sourceId == sourceId }
    override suspend fun sharedKeys(sourceId: String, keyIds: Collection<String>) = keyIds.toSet().let { ids ->
        refs.filter { it.sourceId != sourceId && it.keyId in ids }.map { it.keyId }.toSet()
    }
    override suspend fun plans() = plans.values.toList()
    override suspend fun planKeys(id: String) = planKeys.filter { it.planId == id }
    override suspend fun operations(id: String) = ops.values.filter { it.planId == id }
    override suspend fun insertedRows(rowIds: Collection<Long>) = rowIds.toSet().let { ids ->
        ops.values.filter { it.kind == OperationKind.INSERT && it.returnedId in ids }.mapNotNull { it.returnedId }.toSet()
    }
    override suspend fun prepare(plan: SyncPlan, keys: List<SyncKey>, planKeys: List<PlanKey>) {
        keys.forEach { this.keys[it.id] = it }; plans[plan.id] = plan; this.planKeys.addAll(planKeys); event()
    }
    override suspend fun plan(plan: SyncPlan) {
        plans[plan.id] = plan
        if (plan.purpose != PlanPurpose.APPLY && plan.state == PlanState.CANCELLED) plans[plan.parentId!!] = plans.getValue(plan.parentId).copy(state = PlanState.RECOVERY_REQUIRED)
        event()
    }
    override suspend fun binding(binding: ProviderBinding) { bindings[binding.keyId] = binding; event() }
    override suspend fun operation(op: SyncOperation) { ops[op.id] = op; event() }
    override suspend fun verified(binding: ProviderBinding, op: SyncOperation) { bindings[binding.keyId] = binding; ops[op.id] = op; event() }
    override suspend fun record(bindings: List<ProviderBinding>, ops: List<SyncOperation>) {
        if (bindings.isEmpty() && ops.isEmpty()) return
        bindings.forEach { this.bindings[it.keyId] = it }; ops.forEach { this.ops[it.id] = it }; event()
    }
    override suspend fun validate(plan: SyncPlan) {
        if (stale) throw SyncStopped("PLAN_STALE")
        if (plan.purpose != PlanPurpose.APPLY) {
            val context = RecoveryContext.decode(plan.recoveryContext!!)
            if (referenceVersion() != context.references || context.sources.any { pin -> sources().find { it.id == pin.id } != pin }) throw SyncStopped("RECOVERY_STALE")
        }
    }
    override suspend fun referenceVersion() = RecoveryContext.references(refs)
    override suspend fun sources() = (plans.values.map { it.sourceId } + refs.map { it.sourceId }).distinct().map { id ->
        SyncSourceState(id, 1, refs.firstOrNull { it.sourceId == id }?.snapshotId, SourceLifecycle.ACTIVE)
    }
    override suspend fun prepareRecovery(plan: SyncPlan, keys: List<PlanKey>) {
        plans[plan.parentId!!] = plans.getValue(plan.parentId).copy(state = PlanState.RECOVERING)
        plans[plan.id] = plan; planKeys.addAll(keys); event()
    }
    override suspend fun activateRecovery(plan: SyncPlan, bindings: List<ProviderBinding>) {
        bindings.forEach { this.bindings[it.keyId] = it }; plans[plan.id] = plan; event()
    }
    override suspend fun finishRecovery(plan: SyncPlan) {
        validate(plan)
        val parent = plans.getValue(plan.parentId!!)
        if (plan.purpose == PlanPurpose.RESYNC) { validate(parent); commitData(parent, planKeys(parent.id).filter { it.desired }.map { it.keyId }) }
        else {
            if (plan.purpose == PlanPurpose.REMOVE_SOURCE) refs.removeAll { it.sourceId == parent.sourceId }
            plans[parent.id] = parent.copy(state = PlanState.FAILED_PRESERVED)
            val remaining = refs.map { it.keyId }.toSet()
            bindings.values.filter { it.keyId !in remaining && it.ownership == Ownership.BORROWED && it.conflict == null }
                .forEach { bindings[it.keyId] = it.copy(ownership = Ownership.RELEASED) }
        }
        plans[plan.id] = plan.copy(state = PlanState.COMMITTED); event()
    }
    override suspend fun commit(plan: SyncPlan, keys: List<String>) {
        if (failCommit) throw IllegalStateException("synthetic commit failure")
        validate(plan); commitData(plan, keys); event()
    }
    private fun commitData(plan: SyncPlan, keys: List<String>) {
        refs.removeAll { it.sourceId == plan.sourceId }
        if (plan.newSnapshot != null) refs.addAll(keys.map { SourceReference(plan.sourceId, plan.newSnapshot, it) })
        val remaining = refs.map { it.keyId }.toSet()
        bindings.values.filter { it.keyId !in remaining && it.ownership == Ownership.BORROWED && it.conflict == null }.forEach { bindings[it.keyId] = it.copy(ownership = Ownership.RELEASED) }
        plans[plan.id] = plan.copy(state = PlanState.COMMITTED)
    }
}
class Crash : Error()
class FakeSyncGateway(val store: MemorySyncStore) : SyncGateway {
    val rows = linkedMapOf<Long, ProbeRow>()
    var nextId = 100L
    var inserts = 0
    var deletes = 0
    var access = true
    var fingerprint = "provider1"
    var insertMode = "NORMAL"
    val insertModes = mutableMapOf<Int, String>()
    var deleteMode = "NORMAL"
    var accessChecks = 0
    val reserved = mutableSetOf<Long>()
    override fun reserved(row: ProbeRow) = row.id in reserved
    var reads = 0
    override fun fingerprint() = fingerprint
    override fun checkAccess(): String { accessChecks++; if (!access) throw SyncStopped("READ_UNVERIFIED"); return fingerprint() }
    override fun find(key: SyncKey) = rows.values.filter { key.matches(it) }.also { reads++ }
    override fun byId(id: Long) = rows[id].also { reads++ }
    override fun findAll(keys: Collection<SyncKey>): Map<String, List<ProbeRow>> {
        reads++
        val byWord = rows.values.groupBy { it.word }
        return keys.associate { key -> key.id to byWord[key.word].orEmpty().filter(key::matches) }
    }
    override fun byIds(ids: Collection<Long>) = ids.mapNotNull { rows[it] }.associateBy { it.id }.also { reads++ }
    override fun insert(value: ProbeValue): Long? {
        inserts++
        val id = nextId++
        rows[id] = value.row(id)
        store.event() // death after a Provider write, before the return/ID can be persisted
        when (insertModes[inserts] ?: insertMode) { "THROW" -> throw IllegalStateException(); "NULL" -> return null; "MISMATCH" -> rows[id] = rows.getValue(id).copy(frequency = 200) }
        return id
    }
    override fun delete(row: ProbeRow): Int {
        deletes++
        if (deleteMode == "ZERO") return 0
        if (rows[row.id] != row) return 0
        rows.remove(row.id); store.event()
        if (deleteMode == "THROW") throw IllegalStateException()
        if (deleteMode == "MULTIPLE") return 2
        return 1
    }
}
class SyncEngineTest {
    private val a = SyncKey.of("ja", "人工語A", "よみ")
    private val b = SyncKey.of("ja", "人工語B", "よみ")
    private class Fixture(val batch: Int = SyncEngine.BATCH) {
        val store = MemorySyncStore()
        val gateway = FakeSyncGateway(store)
        fun engine(id: String = "installation1") = SyncEngine(store, gateway, id, batch = batch)
        suspend fun plan(source: String = "A", keys: List<SyncKey>, old: String? = null, snapshot: String? = "s${store.plans.size}") = engine().prepare(source, 1, old, snapshot, "body${store.plans.size}", keys)
        suspend fun apply(p: SyncPreview) = engine().apply(p.plan.id, p.plan.token)
    }
    private suspend fun stopped(code: String, block: suspend () -> Unit) {
        try { block(); fail("expected $code") } catch (e: SyncStopped) { assertEquals(code, e.code) }
    }
    @Test fun planningApplyingAndRecoveryNeverLoadTheWholeKeyTable() = runBlocking {
        val f = Fixture()
        val p = f.plan(keys = listOf(a)); f.apply(p)
        val q = f.plan(keys = listOf(b), old = p.plan.newSnapshot)
        f.gateway.insertMode = "NULL"
        try { f.apply(q); fail() } catch (_: SyncStopped) { }
        f.engine().preview(f.store.plans.getValue(q.plan.id))
        val recovery = f.engine().prepareRecovery(q.plan.id, PlanPurpose.COMPENSATE)
        try { f.engine().apply(recovery.plan.id, recovery.plan.token) } catch (_: SyncStopped) { }
        assertEquals(0, f.store.fullKeyLoads)
    }
    @Test fun managedKeysAreLimitedByTheMemoryBudgetIncludingTheOldNewUnion() = runBlocking {
        val f = Fixture()
        val engine = SyncEngine(f.store, f.gateway, "installation1", maxKeys = 1)
        val p = engine.prepare("A", 1, null, "s0", "body0", listOf(a)); engine.apply(p.plan.id, p.plan.token)
        // Replacing a with b briefly manages both, which this budget cannot hold; nothing is planned or written.
        stopped("KEY_LIMIT") { engine.prepare("A", 1, p.plan.newSnapshot, "s1", "body1", listOf(b)) }
        assertEquals(1, f.gateway.rows.size)
        val roomy = SyncEngine(f.store, f.gateway, "installation1", maxKeys = 2)
        val q = roomy.prepare("A", 1, p.plan.newSnapshot, "s1", "body1", listOf(b))
        roomy.apply(q.plan.id, q.plan.token)
        assertEquals(listOf(b.word), f.gateway.rows.values.map { it.word })
    }
    @Test fun tupleIdentityAndProviderLocalePreserveExactUnicode() {
        assertNotEquals(a.id, b.id)
        assertNotEquals(SyncKey.of("ja", "ab", "c").id, SyncKey.of("ja", "a", "bc").id)
        assertEquals(SyncKey.of("ja", "語", null), SyncKey.of("JA", "語", ""))
        assertNotEquals(a.id, SyncKey.of("ALL", a.word, a.shortcut).id)
        assertNotEquals(SyncKey.of("ja", "é", null).id, SyncKey.of("ja", "e\u0301", null).id)
        for (locale in listOf("ja", "ja-JP", "ALL", "zh-Hant-TW", "en-US-u-ca-gregory")) {
            val k = SyncKey.of(locale, "😀'_%", null)
            assertTrue(k.matches(k.value().row(10)))
        }
        val row = ProbeRow(5, "語'", "", null, null, 0)
        assertEquals(row, SyncCodec.row(SyncCodec.row(row)))
    }
    @Test fun manualAndDuplicateExistingRowsAreBorrowedAndKeptBySourceUpdates() = runBlocking {
        val f = Fixture()
        val original = listOf(a.value().row(1).copy(frequency = 220, shortcut = "よみ"), a.value().row(2))
        original.forEach { f.gateway.rows[it.id] = it }
        val p = f.plan(keys = listOf(a, a)); f.apply(p)
        assertEquals(Ownership.BORROWED, f.store.bindings.getValue(a.id).ownership)
        val u = f.plan(keys = listOf(b), old = p.plan.newSnapshot); assertTrue(u.deletes.isEmpty()); f.apply(u)
        assertEquals(original, f.gateway.rows.values.filter(a::matches)); assertEquals(1, f.gateway.inserts); assertEquals(0, f.gateway.deletes)
        assertEquals(Ownership.RELEASED, f.store.bindings.getValue(a.id).ownership)
    }
    @Test fun removingSourceDeletesItsExistingMatchesIncludingDuplicates() = runBlocking {
        val f = Fixture()
        val other = SyncKey.of("ja", "人工語C", "よみ").value().row(3)
        listOf(a.value().row(1).copy(frequency = 220), a.value().row(2), other).forEach { f.gateway.rows[it.id] = it }
        val p = f.plan(keys = listOf(a, b)); f.apply(p)
        val d = f.plan(keys = emptyList(), old = p.plan.newSnapshot, snapshot = null)
        assertEquals(setOf(a, b), d.deletes.toSet()); assertEquals(0, d.protected)
        f.apply(d)
        assertEquals(listOf(other), f.gateway.rows.values.toList()); assertEquals(3, f.gateway.deletes); assertEquals(1, f.gateway.inserts)
        assertEquals(Ownership.RELEASED, f.store.bindings.getValue(a.id).ownership)
        assertEquals(PlanState.COMMITTED, f.store.plans.getValue(d.plan.id).state)
    }
    @Test fun removingSourceKeepsReservedAndSharedExistingRows() = runBlocking {
        val f = Fixture()
        val witness = a.value().row(1); val copy = a.value().row(2).copy(frequency = 220); val shared = b.value().row(3)
        listOf(witness, copy, shared).forEach { f.gateway.rows[it.id] = it }
        f.gateway.reserved += witness.id
        val pa = f.plan("A", listOf(a, b)); f.apply(pa)
        val pb = f.plan("B", listOf(b)); f.apply(pb)
        f.apply(f.plan("A", emptyList(), pa.plan.newSnapshot, null))
        assertEquals(listOf(witness, shared), f.gateway.rows.values.toList()); assertEquals(1, f.gateway.deletes)
        assertEquals(Ownership.RELEASED, f.store.bindings.getValue(a.id).ownership)
        assertEquals(Ownership.BORROWED, f.store.bindings.getValue(b.id).ownership)
    }
    @Test fun sameFileTenTimesAndMetadataDoNotIncreaseRows() = runBlocking {
        val f = Fixture(); var old: String? = null
        repeat(10) {
            val p = f.plan(keys = listOf(a, b, a), old = old); f.apply(p); old = p.plan.newSnapshot
        }
        assertEquals(2, f.gateway.rows.size); assertEquals(2, f.gateway.inserts); assertEquals(0, f.gateway.deletes)
    }
    @Test fun sharedKeyLivesUntilLastReferenceDisappears() = runBlocking {
        val f = Fixture()
        val pa = f.plan("A", listOf(a)); f.apply(pa)
        val pb = f.plan("B", listOf(a)); f.apply(pb)
        assertEquals(1, f.gateway.inserts); assertEquals(2, f.store.refs.size)
        val da = f.plan("A", emptyList(), pa.plan.newSnapshot, null); assertEquals(1, da.shared); f.apply(da)
        assertEquals(1, f.gateway.rows.size)
        val db = f.plan("B", emptyList(), pb.plan.newSnapshot, null); f.apply(db)
        assertTrue(f.gateway.rows.isEmpty()); assertTrue(f.store.refs.isEmpty()); assertEquals(1, f.gateway.deletes)
    }
    @Test fun externalEditLateInDeletionSetStopsBeforeFirstDeletion() = runBlocking {
        val f = Fixture(); val p = f.plan(keys = listOf(a, b)); f.apply(p)
        val row = f.gateway.rows.values.last(); f.gateway.rows[row.id] = row.copy(frequency = 129)
        val d = f.plan(keys = emptyList(), old = p.plan.newSnapshot)
        stopped("EXTERNAL_EDIT") { f.apply(d) }
        assertEquals(0, f.gateway.deletes); assertEquals(2, f.gateway.rows.size); assertEquals(2, f.store.refs.size)
        assertEquals(Ownership.RELEASED, f.store.bindings.getValue(b.id).ownership)
    }
    @Test fun externalDeletionDoesNotReinsertOrPretendSuccess() = runBlocking {
        val f = Fixture(); val p = f.plan(keys = listOf(a)); f.apply(p); f.gateway.rows.clear()
        val next = f.plan(keys = listOf(a), old = p.plan.newSnapshot)
        stopped("EXTERNAL_DELETE") { f.apply(next) }; assertEquals(1, f.gateway.inserts)
    }
    @Test fun idReuseIsProtectedAndNoReplacementIsDeleted() = runBlocking {
        val f = Fixture(); val p = f.plan(keys = listOf(a)); f.apply(p)
        val row = f.gateway.rows.values.single(); f.gateway.rows[row.id] = row.copy(word = "他人の語")
        val d = f.plan(keys = emptyList(), old = p.plan.newSnapshot)
        stopped("EXTERNAL_EDIT") { f.apply(d) }; assertEquals(0, f.gateway.deletes)
    }
    @Test fun borrowedLossStopsButAnotherMatchingRowCanContinue() = runBlocking {
        val f = Fixture(); f.gateway.rows[1] = a.value().row(1); val p = f.plan(keys = listOf(a)); f.apply(p)
        f.gateway.rows[2] = a.value().row(2); f.gateway.rows.remove(1)
        val n = f.plan(keys = listOf(a), old = p.plan.newSnapshot); f.apply(n)
        f.gateway.rows.clear(); val lost = f.plan(keys = listOf(a), old = n.plan.newSnapshot)
        stopped("BORROWED_LOST") { f.apply(lost) }; assertEquals(0, f.gateway.inserts)
    }
    @Test fun nullOrThrownInsertResultIsNeverAdoptedOrRepeated() = runBlocking {
        for (mode in listOf("NULL", "THROW")) {
            val f = Fixture(); val p = f.plan(keys = listOf(a)); f.gateway.insertMode = mode
            try { f.apply(p); fail() } catch (_: Exception) { }
            stopped("INSERT_UNKNOWN") { f.engine().apply(p.plan.id) }
            stopped("INSERT_UNKNOWN") { f.engine().apply(p.plan.id) }
            assertEquals(1, f.gateway.inserts); assertEquals(0, f.gateway.deletes)
            assertEquals(Ownership.UNKNOWN, f.store.bindings.getValue(a.id).ownership)
        }
    }
    @Test fun insertReadbackMismatchIsNotOwned() = runBlocking {
        val f = Fixture(); val p = f.plan(keys = listOf(a)); f.gateway.insertMode = "MISMATCH"
        stopped("INSERT_READBACK_MISMATCH") { f.apply(p) }
        assertNotEquals(Ownership.OWNED, f.store.bindings.getValue(a.id).ownership)
    }
    @Test fun deleteZeroAndMultipleAreNotSuccessful() = runBlocking {
        for ((mode, code) in listOf("ZERO" to "DELETE_ZERO", "MULTIPLE" to "DELETE_MULTIPLE")) {
            val f = Fixture(); val p = f.plan(keys = listOf(a)); f.apply(p)
            val d = f.plan(keys = emptyList(), old = p.plan.newSnapshot); f.gateway.deleteMode = mode
            stopped(code) { f.apply(d) }; stopped(code) { f.engine().apply(d.plan.id) }
            assertEquals(1, f.store.refs.size); assertEquals(1, f.gateway.deletes)
        }
    }
    @Test fun unknownDeleteConvergesWithoutTouchingOtherId() = runBlocking {
        val f = Fixture(); val p = f.plan(keys = listOf(a)); f.apply(p)
        f.gateway.rows[50] = a.value().row(50) // external same-content row must survive
        val d = f.plan(keys = emptyList(), old = p.plan.newSnapshot); f.gateway.deleteMode = "THROW"
        try { f.apply(d); fail() } catch (_: IllegalStateException) { }
        f.engine().apply(d.plan.id)
        assertEquals(setOf(50L), f.gateway.rows.keys); assertEquals(1, f.gateway.deletes)
    }
    @Test fun approvalTokenIsRequiredForEmptyAndLargeRemovalPlans() = runBlocking {
        val f = Fixture(); val p = f.plan(keys = emptyList())
        stopped("APPROVAL_REQUIRED") { f.engine().apply(p.plan.id) }; f.engine().cancel(p.plan.id)
        val keys = (1..10).map { SyncKey.of("ja", "語$it", "よみ") }
        val a = f.plan(keys = keys); f.apply(a)
        val n = f.plan(keys = keys.drop(2) + b, old = a.plan.newSnapshot)
        stopped("APPROVAL_REQUIRED") { f.engine().apply(n.plan.id, a.plan.token) }; assertEquals(0, f.gateway.deletes)
    }
    @Test fun settingsChangeAndCandidateStalenessCannotReuseApproval() = runBlocking {
        val f = Fixture(); val p = f.engine().prepare("A", 2, null, "snap", "hash", listOf(a))
        f.store.stale = true
        stopped("PLAN_STALE") { f.apply(p) }; assertEquals(0, f.gateway.inserts)
    }
    @Test fun emptyAccessOrProviderChangeHaltsAllMutations() = runBlocking {
        val f = Fixture(); val p = f.plan(keys = listOf(a)); f.gateway.access = false
        stopped("READ_UNVERIFIED") { f.apply(p) }; assertEquals(0, f.gateway.inserts)
        f.gateway.access = true; f.gateway.fingerprint = "newProvider"
        stopped("PROVIDER_CHANGED") { f.engine().apply(p.plan.id) }; assertEquals(0, f.gateway.inserts)
    }
    @Test fun installationMismatchAndLossNeverInheritOwnership() = runBlocking {
        val f = Fixture(); val p = f.plan(keys = listOf(a)); f.apply(p)
        stopped("INSTALLATION_MISMATCH") { f.engine("another").apply(p.plan.id) }
        f.store.installation = null
        stopped("INSTALLATION_LOST") { f.engine().apply(p.plan.id) }; assertEquals(0, f.gateway.deletes)
    }
    @Test fun pendingPlanBlocksAnotherSourceAndCancelAfterWriteIsRejected() = runBlocking {
        val f = Fixture(); val p = f.plan(keys = listOf(a))
        stopped("RECOVER_PLAN_FIRST") { f.plan("B", listOf(b)) }
        f.gateway.insertMode = "NULL"; try { f.apply(p) } catch (_: Exception) { }
        stopped("RECOVER_PLAN_FIRST") { f.engine().cancel(p.plan.id) }
    }
    @Test fun allPersistedStopPointsProtectManualRowsAndNeverBlindlyReinsert() = runBlocking {
        // Each event is after a durable store write or Provider mutation. Restart uses the same store.
        for (stop in 1..28) {
            val f = Fixture(); val manual = b.value().row(2).copy(frequency = 245); f.gateway.rows[2] = manual
            val p = f.plan(keys = listOf(a, b)); f.store.events = 0; f.store.stopAt = stop
            try { f.apply(p) } catch (_: Crash) { }
            f.store.stopAt = Int.MAX_VALUE
            try { f.engine().apply(p.plan.id, p.plan.token) } catch (e: SyncStopped) { assertEquals("INSERT_UNKNOWN", e.code) }
            assertEquals("stop=$stop", manual, f.gateway.rows[2]); assertTrue(f.gateway.inserts <= 1); assertEquals(0, f.gateway.deletes)
        }
    }
    @Test fun deletionStopPointsConvergeWithReferencesCommittedOnlyAtEnd() = runBlocking {
        for (stop in 1..25) {
            val f = Fixture(); val manual = b.value().row(2); f.gateway.rows[2] = manual
            val p = f.plan(keys = listOf(a, b)); f.apply(p)
            val d = f.plan(keys = emptyList(), old = p.plan.newSnapshot, snapshot = null)
            f.store.events = 0; f.store.stopAt = stop
            try { f.apply(d) } catch (_: Crash) { }
            f.store.stopAt = Int.MAX_VALUE
            f.engine().apply(d.plan.id, d.plan.token)
            // The existing row goes with its source, as the app's own row does; neither delete is issued twice.
            assertTrue("stop=$stop", f.gateway.rows.isEmpty()); assertTrue(f.store.refs.isEmpty()); assertEquals("stop=$stop", 2, f.gateway.deletes)
        }
    }
    @Test fun seededSourceSequencesConvergeToUnionWithoutChangingManualRows() = runBlocking {
        val f = Fixture()
        val keys = (0..7).map { SyncKey.of(listOf("ja", "ja-JP", "en", "ALL")[it % 4], "合成$it", if (it % 3 == 0) null else "同じ読み") }
        val manual = keys.first().value().row(1).copy(frequency = 231)
        f.gateway.rows[1] = manual
        val desired = mutableMapOf("A" to emptySet<SyncKey>(), "B" to emptySet())
        val snapshots = mutableMapOf<String, String?>()
        val random = java.util.Random(17)
        repeat(80) {
            val source = if (random.nextBoolean()) "A" else "B"
            val wanted = keys.filter { random.nextInt(3) == 0 }.toSet()
            val p = f.plan(source, wanted.toList(), snapshots[source])
            f.apply(p); snapshots[source] = p.plan.newSnapshot; desired[source] = wanted
            val union = desired.values.flatten().toSet()
            assertEquals(union.filter { it != keys.first() }.map { it.id }.toSet(),
                f.gateway.rows.values.filter { it.id != 1L }.mapNotNull { SyncKey.from(it)?.id }.toSet())
            assertEquals(manual, f.gateway.rows[1]); assertEquals(union.size, f.store.refs.map { it.keyId }.distinct().size)
        }
    }
    @Test fun auxiliaryHashCollisionDoesNotMergeDifferentOriginalTuples() = runBlocking {
        val f = Fixture(); val p = f.plan(keys = listOf(a.copy(digest = "same"), b.copy(digest = "same"))); f.apply(p)
        assertEquals(2, f.gateway.rows.size); assertEquals(2, f.store.bindings.size)
    }

    @Test fun borrowedRowReleasedWithLastReferenceDoesNotBlockFutureExplicitAddition() = runBlocking {
        val f = Fixture(); f.gateway.rows[1] = a.value().row(1)
        val p = f.plan(keys = listOf(a)); f.apply(p)
        val d = f.plan(keys = emptyList(), old = p.plan.newSnapshot, snapshot = null); f.apply(d)
        assertEquals(Ownership.RELEASED, f.store.bindings.getValue(a.id).ownership)
        f.gateway.rows.clear()
        val n = f.plan("B", listOf(a)); f.apply(n)
        assertEquals(1, f.gateway.inserts); assertEquals(Ownership.OWNED, f.store.bindings.getValue(a.id).ownership)
    }

    @Test fun largeSourcesUseBatchedProviderAccessNotOnePerWord() = runBlocking {
        val f = Fixture()
        val keys = (1..5_000).map { SyncKey.of("ja", "大量$it", "たいりょう") }
        val p = f.plan(keys = keys); f.apply(p)
        assertEquals(5_000, f.gateway.inserts); assertEquals(5_000, f.gateway.rows.size)
        // 6 batches of 900. A few access checks and reads per batch, never per word.
        assertTrue("access ${f.gateway.accessChecks}", f.gateway.accessChecks < 100)
        assertTrue("reads ${f.gateway.reads}", f.gateway.reads < 100)
        f.gateway.accessChecks = 0; f.gateway.reads = 0
        val unchanged = f.plan(keys = keys, old = p.plan.newSnapshot); f.apply(unchanged)
        assertEquals(5_000, f.gateway.inserts)
        assertTrue("access ${f.gateway.accessChecks}", f.gateway.accessChecks < 100)
        f.gateway.accessChecks = 0
        val replaced = f.plan(keys = keys.take(1_000), old = unchanged.plan.newSnapshot, snapshot = "replaced"); f.apply(replaced)
        assertEquals(1_000, f.gateway.rows.size); assertEquals(4_000, f.gateway.deletes)
        assertTrue("access ${f.gateway.accessChecks}", f.gateway.accessChecks < 100)
    }
    @Test fun stopPointsAcrossBatchBoundariesNeverBlindlyReinsertOrTouchManualRows() = runBlocking {
        val keys = (1..5).map { SyncKey.of("ja", "境界$it", "きょうかい") }
        for (stop in 1..40) {
            val f = Fixture(batch = 2); val manual = keys[2].value().row(2).copy(frequency = 245); f.gateway.rows[2] = manual
            val p = f.plan(keys = keys); f.store.events = 0; f.store.stopAt = stop
            try { f.apply(p) } catch (_: Crash) { }
            f.store.stopAt = Int.MAX_VALUE
            try { f.engine().apply(p.plan.id, p.plan.token) } catch (e: SyncStopped) { assertEquals("INSERT_UNKNOWN", e.code) }
            assertEquals("stop=$stop", manual, f.gateway.rows[2]); assertTrue("stop=$stop", f.gateway.inserts <= 4)
            assertEquals(0, f.gateway.deletes)
            val rows = f.gateway.rows.values.groupBy { SyncKey.from(it)?.id }
            assertTrue("stop=$stop", rows.values.all { it.size == 1 })
        }
        for (stop in 1..40) {
            val f = Fixture(batch = 2); val p = f.plan(keys = keys); f.apply(p)
            val d = f.plan(keys = keys.take(1), old = p.plan.newSnapshot, snapshot = null)
            f.store.events = 0; f.store.stopAt = stop
            try { f.apply(d) } catch (_: Crash) { }
            f.store.stopAt = Int.MAX_VALUE
            f.engine().apply(d.plan.id, d.plan.token)
            assertEquals("stop=$stop", listOf(keys[0].id), f.gateway.rows.values.map { SyncKey.from(it)!!.id }); assertEquals(4, f.gateway.deletes)
        }
    }
    @Test fun inFlightInsertWithoutRowIsRetriedButWithRowStaysUnknown() = runBlocking {
        val f = Fixture(); val p = f.plan(keys = listOf(a, b))
        f.gateway.insertModes[1] = "THROW" // The first row exists but its ID is lost; the second call is never made.
        try { f.apply(p); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, f.gateway.inserts)
        assertEquals(listOf(OperationPhase.IN_FLIGHT, OperationPhase.IN_FLIGHT), f.store.operations(p.plan.id).map { it.phase })
        stopped("INSERT_UNKNOWN") { f.engine().apply(p.plan.id) }
        assertEquals(1, f.gateway.inserts)
        val f2 = Fixture(); val q = f2.plan(keys = listOf(a, b))
        f2.store.events = 0; f2.store.stopAt = 1 // Process death right after both inserts are journalled IN_FLIGHT.
        try { f2.apply(q) } catch (_: Crash) { }
        f2.store.stopAt = Int.MAX_VALUE
        f2.engine().apply(q.plan.id)
        assertEquals(2, f2.gateway.inserts); assertEquals(setOf(Ownership.OWNED), f2.store.bindings.values.map { it.ownership }.toSet())
    }
}
