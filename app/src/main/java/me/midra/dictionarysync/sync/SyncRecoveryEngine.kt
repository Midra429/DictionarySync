package me.midra.dictionarysync.sync

import java.util.UUID
import me.midra.dictionarysync.data.local.SourceLifecycle
import me.midra.dictionarysync.data.local.enumOrNull

/** Pinned consent includes source generations, original ownership, all current matches and operation results.
 * It is local recovery history, never a source from which ownership can be inferred. */
internal data class RecoveryContext(
    val references: String, val sources: List<SyncSourceState>, val evidence: String,
    val reset: Set<String>, val blocked: Map<String, String>,
) {
    fun encode() = SyncCodec.pack(listOf("1", references,
        SyncCodec.pack(sources.sortedBy { it.id }.map { SyncCodec.pack(listOf(it.id, it.revision.toString(), it.activeSnapshot, it.lifecycle.name)) }),
        evidence, SyncCodec.pack(reset.sorted()),
        SyncCodec.pack(blocked.toSortedMap().map { SyncCodec.pack(listOf(it.key, it.value)) })))
    companion object {
        fun decode(value: String): RecoveryContext {
            val fields = SyncCodec.unpack(value)
            require(fields.size == 6 && fields[0] == "1")
            return RecoveryContext(fields[1]!!, SyncCodec.unpack(fields[2]!!).map { value ->
                val s = SyncCodec.unpack(value!!); SyncSourceState(s[0]!!, s[1]!!.toLong(), s[2], enumOrNull(s[3]!!) ?: SourceLifecycle.ACTIVE)
            }, fields[3]!!, SyncCodec.unpack(fields[4]!!).map { it!! }.toSet(),
                SyncCodec.unpack(fields[5]!!).associate { value -> SyncCodec.unpack(value!!).let { it[0]!! to it[1]!! } })
        }
        fun references(refs: List<SourceReference>) = SyncCodec.hash(refs.map {
            SyncCodec.pack(listOf(it.sourceId, it.snapshotId, it.keyId))
        }.sorted())
    }
}

/** A recovery is a separate durable plan. The original journal and successful generation are retained. */
internal class SyncRecoveryEngine(private val store: SyncStore, private val gateway: SyncGateway,
    private val installationId: String, private val engine: SyncEngine, private val now: () -> Long) {
    suspend fun prepare(id: String, purpose: PlanPurpose): SyncPreview {
        require(purpose.recovery)
        engine.installation()
        val parent = store.plans().single { it.id == id }
        if (parent.installationId != installationId) throw SyncStopped("INSTALLATION_MISMATCH")
        if (parent.purpose.recovery || parent.state.terminal || !parent.approved) throw SyncStopped("RECOVERY_NOT_AVAILABLE")
        if (store.plans().any { it.id != id && !it.state.terminal }) throw SyncStopped("RECOVER_PLAN_FIRST")
        engine.boundary(parent)
        val parentKeys = store.planKeys(id)
        val refs = store.references()
        val involved = parentKeys.map { it.keyId }.toMutableSet()
        val sourceIds = mutableSetOf(parent.sourceId)
        // Include the connected reference group, so a conflict shared through another source is not hidden.
        do {
            val size = involved.size + sourceIds.size
            refs.filter { it.sourceId in sourceIds }.forEach { involved += it.keyId }
            refs.filter { it.keyId in involved }.forEach { sourceIds += it.sourceId }
        } while (size != involved.size + sourceIds.size)
        val operations = store.operations(id)
        val all = store.keys(involved + operations.map { it.keyId }).associateBy { it.id }
        val blocked = settle(parent, all).toMutableMap()
        val reset = mutableSetOf<String>()
        engine.audit(involved.map { all.getValue(it) }, parent) { key, e ->
            if (e.code !in EXTERNAL) {
                if (e.code !in KEY_FAILURES) throw e
                blocked[key.id] = e.code
            }
        }
        val referenced = refs.map { it.keyId }.toSet()
        val bindings = store.bindings(involved).associateBy { it.keyId }
        val deleted = store.operations(id).filter { it.kind == OperationKind.DELETE && it.phase == OperationPhase.VERIFIED }.map { it.keyId }.toSet()
        for (keyId in involved) {
            val binding = bindings[keyId]
            if (binding == null && keyId in referenced) blocked[keyId] = "BINDING_MISSING"
            if (binding?.conflict in EXTERNAL && keyId !in blocked) {
                if (purpose == PlanPurpose.RESYNC) reset += keyId else blocked[keyId] = binding!!.conflict!!
            } else if (binding?.conflict != null) blocked[keyId] = binding.conflict
            if (binding?.ownership == Ownership.UNKNOWN) blocked.putIfAbsent(keyId, "OWNERSHIP_UNKNOWN")
            if (purpose == PlanPurpose.COMPENSATE && binding?.ownership == Ownership.RELEASED && binding.conflict == null && keyId in referenced && keyId !in deleted) {
                blocked[keyId] = "OWNERSHIP_UNKNOWN"
            }
        }
        val desired = when (purpose) {
            PlanPurpose.REMOVE_SOURCE -> emptySet()
            PlanPurpose.RESYNC -> parentKeys.filter { it.desired }.map { it.keyId }.toSet() +
                refs.filter { it.sourceId != parent.sourceId && it.sourceId in sourceIds }.map { it.keyId }
            else -> refs.filter { it.sourceId in sourceIds }.map { it.keyId }.toSet()
        }
        val removed = when (purpose) {
            PlanPurpose.REMOVE_SOURCE -> {
                // Include partial additions, which have no committed source reference yet.
                val shared = refs.filter { it.sourceId != parent.sourceId }.map { it.keyId }.toSet()
                (parentKeys.map { it.keyId } + refs.filter { it.sourceId == parent.sourceId }.map { it.keyId }).toSet() - shared
            }
            PlanPurpose.RESYNC -> parentKeys.filter { !it.desired && it.keyId !in desired }.map { it.keyId }.toSet()
            else -> store.operations(id).filter { it.kind == OperationKind.INSERT && it.keyId !in desired }.map { it.keyId }.toSet()
        }
        val keyIds = desired + removed
        // Non-terminal insert outcomes remain blockers even if the key is no longer needed.
        val context = RecoveryContext(store.referenceVersion(), store.sources().filter { it.id in sourceIds },
            evidence(parent, involved, all), reset, blocked)
        if (context.sources.map { it.id }.toSet() != sourceIds) throw SyncStopped("SOURCE_MISSING")
        val token = SyncCodec.hash(listOf(parent.id, parent.token, purpose.name, context.encode(), SyncCodec.pack(desired.sorted()), SyncCodec.pack(removed.sorted())))
        val target = when (purpose) {
            PlanPurpose.RESYNC -> parent.newSnapshot
            PlanPurpose.REMOVE_SOURCE -> null
            else -> parent.oldSnapshot
        }
        val plan = SyncPlan(UUID.randomUUID().toString(), parent.sourceId, parent.revision, parent.oldSnapshot, target, installationId, parent.fingerprint,
            token, createdAt = now(), purpose = purpose, parentId = id, recoveryContext = context.encode())
        // Include blocked keys in the immutable context, even if they do not produce an operation.
        check(keyIds.all { it in involved })
        store.prepareRecovery(plan, desired.map { PlanKey(plan.id, it, true) } + removed.map { PlanKey(plan.id, it, false) })
        return preview(plan)
    }

    /** Resolve only recorded outcomes. This inspection never reissues a Provider operation. */
    private suspend fun settle(parent: SyncPlan, keys: Map<String, SyncKey>): Map<String, String> {
        val blocked = mutableMapOf<String, String>()
        val operations = store.operations(parent.id)
        val bindings = store.bindings(operations.map { it.keyId }).associateBy { it.keyId }
        val audited = mutableListOf<SyncKey>()
        val unsent = mutableListOf<SyncOperation>()
        val deletes = mutableListOf<SyncOperation>()
        for (op in operations) {
            val key = keys.getValue(op.keyId)
            if (op.phase == OperationPhase.PREPARED) continue
            if (op.kind == OperationKind.INSERT) {
                if (op.error == SyncEngine.EXISTING_MATCH) continue
                if (op.returnedId == null && op.phase == OperationPhase.IN_FLIGHT) { unsent += op; continue }
                if (op.returnedId == null || op.phase == OperationPhase.AMBIGUOUS) { blocked[op.keyId] = "INSERT_UNKNOWN"; continue }
                if (op.phase == OperationPhase.VERIFIED) {
                    val binding = bindings[op.keyId]
                    if (binding == null || (binding.ownership == Ownership.RELEASED && binding.conflict == null)) {
                        blocked[op.keyId] = "BINDING_MISSING"; continue
                    }
                    audited += key
                    continue
                }
                engine.boundary(parent)
                try { engine.reconcile(op, key, parent) } catch (e: SyncStopped) {
                    if (e.code !in KEY_FAILURES && e.code !in EXTERNAL) throw e
                    blocked[op.keyId] = e.code
                }
            } else {
                if (op.result != null && op.result != 1) {
                    blocked[op.keyId] = if (op.result == 0) "DELETE_ZERO" else "DELETE_MULTIPLE"; continue
                }
                deletes += op
            }
        }
        engine.audit(audited, parent) { key, e ->
            if (e.code !in EXTERNAL) {
                if (e.code !in KEY_FAILURES) throw e
                blocked[key.id] = e.code
            }
        }
        // An insert journalled IN_FLIGHT without an ID never took effect if no matching row exists; like PREPARED,
        // it is left to the recovery plan. A matching row may be ours or someone else's.
        for (chunk in unsent.chunked(BATCH)) {
            engine.boundary(parent)
            val found = engine.find(chunk.map { keys.getValue(it.keyId) }, parent)
            for (op in chunk) if (found.getValue(op.keyId).isNotEmpty()) blocked[op.keyId] = "INSERT_UNKNOWN"
        }
        for (chunk in deletes.chunked(BATCH)) {
            engine.boundary(parent)
            val expected = chunk.associate { it.id to (it.expected?.let(SyncCodec::row) ?: throw SyncStopped("OWNERSHIP_UNKNOWN")) }
            val rows = gateway.byIds(expected.values.map { it.id })
            val released = mutableListOf<Pair<ProviderBinding, SyncOperation>>()
            for (op in chunk) {
                val row = expected.getValue(op.id)
                val actual = rows[row.id]
                if (actual == null) {
                    released += requireNotNull(bindings[op.keyId]).copy(ownership = Ownership.RELEASED, observedAt = now()) to op.copy(phase = OperationPhase.VERIFIED)
                } else if (actual != row || op.result == 1 || op.phase == OperationPhase.VERIFIED) {
                    blocked[op.keyId] = "DELETE_READBACK_MISMATCH"
                }
            }
            store.record(released.map { it.first }, released.map { it.second })
        }
        return blocked
    }

    private suspend fun evidence(parent: SyncPlan, involved: Set<String>, keys: Map<String, SyncKey>): String {
        val ids = involved.sorted()
        val bindings = store.bindings(ids).associateBy { it.keyId }
        val rows = ids.chunked(BATCH).flatMap { chunk ->
            engine.access(parent)
            val observed = chunk.associateWith { id -> bindings[id]?.observed?.let(SyncCodec::row) }
            val current = gateway.byIds(observed.values.mapNotNull { it?.id })
            val found = engine.find(chunk.map { keys.getValue(it) }, parent)
            chunk.map { id ->
                val binding = bindings[id]
                SyncCodec.pack(listOf(id, binding?.installationId, binding?.authority, binding?.ownership?.name, binding?.observed,
                    binding?.conflict, observed.getValue(id)?.let { current[it.id] }?.let(SyncCodec::row),
                    SyncCodec.pack(found.getValue(id).sortedBy { it.id }.map(SyncCodec::row))))
            }
        }
        val operations = store.operations(parent.id).sortedBy { it.id }.map {
            SyncCodec.pack(listOf(it.id, it.keyId, it.kind.name, it.phase.name, it.expected, it.returnedId?.toString(), it.result?.toString(), it.attempts.toString(), it.error))
        }
        return SyncCodec.pack(listOf(SyncCodec.pack(rows), SyncCodec.pack(operations)))
    }

    suspend fun preview(plan: SyncPlan): SyncPreview {
        val context = RecoveryContext.decode(requireNotNull(plan.recoveryContext))
        val keys = store.planKeys(plan.id)
        val all = store.keys(keys.map { it.keyId }).associateBy { it.id }
        val bindings = store.bindings(keys.map { it.keyId }).associateBy { it.keyId }
        val wanted = keys.filter { it.desired }.map { all.getValue(it.keyId) }
        val removed = keys.filter { !it.desired }.map { all.getValue(it.keyId) }
        return SyncPreview(plan, wanted.filter { SyncEngine.needsInsert(bindings[it.id]) || it.id in context.reset }, removed,
            removed.filter { bindings[it.id]?.ownership in SyncEngine.deletable(plan) && it.id !in context.blocked },
            context.reset.size + context.blocked.size, 0, wanted.size, context.sources.map { it.id }, context.blocked.values.distinct().sorted())
    }

    suspend fun apply(initial: SyncPlan, token: String?) {
        if (initial.state == PlanState.COMMITTED) return
        if (initial.state.terminal) throw SyncStopped("PLAN_CLOSED")
        if (initial.installationId != installationId) throw SyncStopped("INSTALLATION_MISMATCH")
        if (store.plans().any { it.id !in setOf(initial.id, initial.parentId) && !it.state.terminal }) throw SyncStopped("RECOVER_PLAN_FIRST")
        var plan = initial
        val context = RecoveryContext.decode(requireNotNull(plan.recoveryContext))
        val parent = store.plans().single { it.id == plan.parentId }
        val involvedKeys = SyncCodec.unpack(SyncCodec.unpack(context.evidence)[0]!!).map { SyncCodec.unpack(it!!)[0]!! }
        val all = store.keys(store.planKeys(plan.id).map { it.keyId } + involvedKeys + store.operations(plan.id).map { it.keyId })
            .associateBy { it.id }
        if (!plan.approved) {
            engine.boundary(plan)
            if (token != plan.token) throw SyncStopped("APPROVAL_REQUIRED")
            if (plan.purpose == PlanPurpose.RESYNC && context.blocked.isNotEmpty()) throw SyncStopped("RESYNC_BLOCKED")
            val involved = involvedKeys.toSet()
            if (evidence(parent, involved, all) != context.evidence) throw SyncStopped("RECOVERY_STALE")
            val bindings = store.bindings(context.reset).map {
                if (it.conflict !in EXTERNAL || it.ownership !in setOf(Ownership.RELEASED, Ownership.BORROWED)) throw SyncStopped("OWNERSHIP_UNKNOWN")
                it.copy(ownership = Ownership.RELEASED, observed = null, conflict = null, observedAt = now())
            }
            plan = plan.copy(approved = true, state = PlanState.ADDING)
            store.activateRecovery(plan, bindings)
        }
        try {
            engine.boundary(plan)
            val keys = store.planKeys(plan.id).filter { it.keyId !in context.blocked }
            val desired = keys.filter { it.desired }.map { all.getValue(it.keyId) }
            val removed = keys.filter { !it.desired }.map { all.getValue(it.keyId) }
            val operations = store.operations(plan.id).associateBy { it.keyId }
            val involved = desired + removed
            engine.reconcile(involved.mapNotNull { operations[it.id] }, all, plan)
            engine.audit(involved.filter { it.id !in operations }, plan)
            for (chunk in desired.chunked(BATCH)) engine.add(chunk, plan)
            // Restore all provable old keys before undoing any additions from the failed attempt.
            engine.audit(desired, plan)
            store.plan(plan.copy(state = PlanState.REMOVING, error = null))
            val removalOperations = store.operations(plan.id).associateBy { it.keyId }
            val deletable = SyncEngine.deletable(plan)
            for (chunk in removed.chunked(BATCH)) {
                engine.boundary(plan)
                engine.reconcile(chunk.mapNotNull { removalOperations[it.id] }, all, plan)
                val rest = chunk.filter { it.id !in removalOperations }
                val bindings = store.bindings(rest.map { it.id }).associateBy { it.keyId }
                val bound = rest.filter { it.id in bindings }
                engine.audit(bound, plan)
                // Only known, unchanged OWNED rows (and, removing the source, its existing matches) that no target source
                // still needs are removed.
                engine.delete(engine.current(bound.filter { bindings.getValue(it.id).ownership in deletable }.map { it to bindings.getValue(it.id) }, plan), plan)
            }
            engine.boundary(plan)
            engine.audit(desired, plan)
            engine.reconcile(store.operations(plan.id), all, plan)
            // Explicit source removal preserves uncertain/external rows and their journal.
            // It does not claim that the failed import was successfully restored.
            if (context.blocked.isNotEmpty() && plan.purpose != PlanPurpose.REMOVE_SOURCE) throw SyncStopped("COMPENSATION_INCOMPLETE")
            store.finishRecovery(plan)
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) store.plan(plan.copy(state = PlanState.RECOVERY_REQUIRED, error = SyncStopped.code(e)))
            throw e
        }
    }
    companion object {
        val EXTERNAL = setOf("EXTERNAL_EDIT", "EXTERNAL_DELETE", "EXTERNAL_EDIT_OR_DELETE", "BORROWED_LOST")
        private const val BATCH = SyncEngine.BATCH
        private val KEY_FAILURES = setOf("INSERT_READBACK_MISMATCH", "INSERT_UNKNOWN", "OWNERSHIP_UNKNOWN", "DELETE_READBACK_MISMATCH", "DELETE_ZERO", "DELETE_MULTIPLE")
    }
}
