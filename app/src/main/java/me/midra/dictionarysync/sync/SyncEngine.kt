package me.midra.dictionarysync.sync

import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.midra.dictionarysync.probe.ProbeRow

/**
 * Caller holds the process mutex AND OS file lock for the entire operation, including recovery.
 * Keys are processed in batches of [batch]: each batch checks access and plan validity once and reads the Provider
 * with a few IN queries, so a 100,000-word source does not cost one access check and table scan per word.
 */
class SyncEngine(private val store: SyncStore, private val gateway: SyncGateway, private val installationId: String,
    private val now: () -> Long = System::currentTimeMillis, private val batch: Int = BATCH) {
    internal suspend fun installation() {
        val id = store.installation()
        if (id == null) {
            if (store.bindings().isNotEmpty() || store.plans().isNotEmpty()) throw SyncStopped("INSTALLATION_LOST")
            store.installation(installationId)
        } else if (id != installationId) throw SyncStopped("INSTALLATION_MISMATCH")
    }
    internal fun access(plan: SyncPlan? = null) {
        val current = gateway.checkAccess()
        if (plan != null && current != plan.fingerprint) throw SyncStopped("PROVIDER_CHANGED")
    }
    suspend fun prepare(sourceId: String, revision: Long, oldSnapshot: String?, snapshot: String?,
        bodyHash: String, desired: List<SyncKey>, retainSource: Boolean = false): SyncPreview =
        planned(sourceId, revision, oldSnapshot, snapshot, bodyHash, desired, retainSource).let { (plan, keys) -> preview(plan, keys) }
    /** [prepare] without the preview, for callers that apply right away and never show it. */
    suspend fun plan(sourceId: String, revision: Long, oldSnapshot: String?, snapshot: String?,
        bodyHash: String, desired: List<SyncKey>, retainSource: Boolean = false): SyncPlan =
        planned(sourceId, revision, oldSnapshot, snapshot, bodyHash, desired, retainSource).first
    private suspend fun planned(sourceId: String, revision: Long, oldSnapshot: String?, snapshot: String?,
        bodyHash: String, desired: List<SyncKey>, retainSource: Boolean): Pair<SyncPlan, Map<String, SyncKey>> {
        installation(); access()
        if (store.plans().any { !it.state.terminal }) throw SyncStopped("RECOVER_PLAN_FIRST")
        val old = store.references(sourceId).map { it.keyId }.toSet()
        val wanted = desired.distinctBy { it.id }
        val removed = old - wanted.map { it.id }.toSet()
        val allKeys = (store.keys(removed) + wanted).associateBy { it.id }
        // Include the transient old/new union. Never free another application's rows to fit a limit.
        if (store.ownedCount() + wanted.size - store.ownedCount(wanted.map { it.id }) > KEY_LIMIT) throw SyncStopped("KEY_LIMIT")
        // The reference generation stands for every source's references at planning time.
        val token = SyncCodec.hash(listOf(sourceId, revision.toString(), oldSnapshot, snapshot, bodyHash,
            SyncCodec.pack(wanted.map { it.id }.sorted()), SyncCodec.pack(removed.sorted()), store.referenceVersion()))
        val plan = SyncPlan(UUID.randomUUID().toString(), sourceId, revision, oldSnapshot, snapshot, installationId,
            gateway.fingerprint(), token, createdAt = now(), retainSource = retainSource)
        store.prepare(plan, wanted, wanted.map { PlanKey(plan.id, it.id, true) } + removed.map { PlanKey(plan.id, it, false) })
        return plan to allKeys
    }
    suspend fun preview(plan: SyncPlan, knownKeys: Map<String, SyncKey>? = null): SyncPreview {
        if (plan.purpose.recovery) return recovery().preview(plan)
        val keys = store.planKeys(plan.id)
        val allKeys = knownKeys ?: store.keys(keys.map { it.keyId }).associateBy { it.id }
        val wanted = keys.filter { it.desired }.map { allKeys.getValue(it.keyId) }
        val removed = keys.filter { !it.desired }.map { allKeys.getValue(it.keyId) }
        val other = store.sharedKeys(plan.sourceId, removed.map { it.id })
        val bindings = store.bindings(keys.map { it.keyId }).associateBy { it.keyId }
        val deletes = removed.filter { it.id !in other && bindings[it.id]?.ownership == Ownership.OWNED }
        return SyncPreview(plan, wanted.filter { needsInsert(bindings[it.id]) }, removed,
            deletes, removed.count { bindings[it.id]?.ownership != Ownership.OWNED }, removed.count { it.id in other }, wanted.size)
    }
    suspend fun cancel(id: String) {
        val plan = store.plans().single { it.id == id }
        if (store.plans().any { it.parentId == id && !it.state.terminal }) throw SyncStopped("RECOVER_PLAN_FIRST")
        if (store.operations(id).isNotEmpty()) throw SyncStopped("RECOVER_PLAN_FIRST")
        if (plan.state.terminal || (plan.purpose.recovery && plan.approved)) throw SyncStopped("PLAN_CLOSED")
        store.plan(plan.copy(state = PlanState.CANCELLED, finishedAt = now()))
    }
    suspend fun apply(id: String, approvalToken: String? = null) {
        installation()
        var plan = store.plans().single { it.id == id }
        if (plan.purpose.recovery) { recovery().apply(plan, approvalToken); return }
        if (plan.state == PlanState.COMMITTED) return
        if (plan.state.terminal) throw SyncStopped("PLAN_CLOSED")
        if (plan.installationId != installationId) throw SyncStopped("INSTALLATION_MISMATCH")
        if (store.plans().any { it.id != id && !it.state.terminal }) throw SyncStopped("RECOVER_PLAN_FIRST")
        if (!plan.approved) {
            // A new fetch invalidates an unapproved preview. An in-progress plan keeps its pinned candidate.
            store.validate(plan)
            // Bind the apply call to this exact prepared candidate (including automatic callers).
            if (approvalToken != plan.token) throw SyncStopped("APPROVAL_REQUIRED")
            plan = plan.copy(approved = true)
            store.plan(plan)
        }
        try {
            access(plan); store.validate(plan)
            val keys = store.planKeys(id)
            val allKeys = store.keys(keys.map { it.keyId } + store.operations(id).map { it.keyId }).associateBy { it.id }
            val wanted = keys.filter { it.desired }.map { allKeys.getValue(it.keyId) }
            val removed = keys.filter { !it.desired }.map { allKeys.getValue(it.keyId) }
            val operations = store.operations(id).associateBy { it.keyId }
            // Audit every involved existing binding before the first mutation, including late removals.
            val involved = (wanted + removed).distinctBy { it.id }
            val unchanged = changes
            reconcile(involved.mapNotNull { operations[it.id] }, allKeys, plan)
            audit(involved.filter { it.id !in operations }, plan)
            store.plan(plan.copy(state = PlanState.ADDING, error = null))
            for (chunk in wanted.chunked(batch)) add(chunk, plan)
            // New keys must all be confirmed before releasing any old reference or deleting anything.
            // When nothing was written, the audit above already covers every key.
            if (changes != unchanged) audit(wanted, plan)
            val removalOperations = store.operations(id).associateBy { it.keyId }
            reconcile(removed.mapNotNull { removalOperations[it.id] }, allKeys, plan)
            audit(removed.filter { it.id !in removalOperations }, plan)
            store.plan(plan.copy(state = PlanState.REMOVING, error = null))
            val shared = store.sharedKeys(plan.sourceId, removed.map { it.id })
            for (chunk in removed.filter { it.id !in shared }.chunked(batch)) {
                boundary(plan)
                val existing = chunk.mapNotNull { key -> removalOperations[key.id]?.takeIf { it.kind == OperationKind.DELETE } }
                reconcile(existing, allKeys, plan)
                val reconciled = existing.map { it.keyId }.toSet()
                val rest = chunk.filter { it.id !in reconciled }
                val bindings = store.bindings(rest.map { it.id }).associateBy { it.keyId }
                val targets = rest.filter { key ->
                    val binding = bindings[key.id] ?: throw SyncStopped("BINDING_MISSING")
                    // Rows the app never inserted, or no longer holds, are released rather than deleted.
                    !(binding.ownership in setOf(Ownership.BORROWED, Ownership.RELEASED) && binding.conflict == null)
                }
                audit(targets, plan)
                for (key in targets) {
                    val binding = bindings.getValue(key.id)
                    if (binding.ownership != Ownership.OWNED || binding.installationId != installationId) throw SyncStopped("OWNERSHIP_UNKNOWN")
                }
                delete(targets.map { it to bindings.getValue(it.id) }, plan)
            }
            boundary(plan)
            if (changes != unchanged) audit(wanted, plan)
            reconcile(store.operations(id), allKeys, plan)
            store.commit(plan, wanted.map { it.id })
        } catch (error: Exception) {
            // A crash/DB failure can leave IN_FLIGHT intact. Never convert it back to PREPARED.
            // Cancellation leaves the plan in its current non-terminal phase; a later background pass resumes it.
            if (error !is kotlinx.coroutines.CancellationException) store.plan(plan.copy(state = PlanState.RECOVERY_REQUIRED, error = SyncStopped.code(error)))
            throw error
        }
    }
    /** Counts writes (bindings and Provider calls) made by this engine, so audits that cannot find anything new are skipped. */
    private var changes = 0
    internal suspend fun boundary(plan: SyncPlan) {
        currentCoroutineContext().ensureActive(); access(plan); store.validate(plan)
    }
    internal fun find(key: SyncKey, plan: SyncPlan): List<ProbeRow> = find(listOf(key), plan).getValue(key.id)
    /** One access check, then the Provider's rows for every key; each key's rows are checked as [find] always did. */
    internal fun find(keys: Collection<SyncKey>, plan: SyncPlan): Map<String, List<ProbeRow>> {
        if (keys.isEmpty()) return emptyMap()
        access(plan)
        val found = gateway.findAll(keys)
        for (key in keys) {
            val rows = found[key.id] ?: throw SyncStopped("QUERY_MISMATCH")
            if (rows.map { it.id }.distinct().size != rows.size || rows.any { !key.matches(it) }) throw SyncStopped("QUERY_MISMATCH")
        }
        return found
    }
    /**
     * Adds one batch of wanted keys. Keys that already have a binding were audited before this phase and are audited
     * again after it. The Provider is searched once for the whole batch right before its inserts, so a row inserted
     * externally after planning becomes BORROWED.
     */
    internal suspend fun add(chunk: List<SyncKey>, plan: SyncPlan) {
        boundary(plan)
        val bindings = store.bindings(chunk.map { it.id }).associateBy { it.keyId }
        val missing = chunk.filter { needsInsert(bindings[it.id]) }
        for (key in missing) bindings[key.id]?.conflict?.let { throw SyncStopped(it) }
        val found = find(missing, plan)
        val borrowed = missing.filter { found.getValue(it.id).isNotEmpty() }.map {
            ProviderBinding(it.id, installationId, ownership = Ownership.BORROWED, observed = SyncCodec.row(found.getValue(it.id).first()), observedAt = now())
        }
        val inserts = missing.filter { found.getValue(it.id).isEmpty() }
        // Journalled as IN_FLIGHT before the first call: an IN_FLIGHT insert whose row is absent never took effect.
        val ops = inserts.map { key ->
            val value = key.value()
            SyncOperation(UUID.randomUUID().toString(), plan.id, key.id, OperationKind.INSERT, phase = OperationPhase.IN_FLIGHT, attempts = 1,
                expected = SyncCodec.pack(listOf(ABSENT, value.word, value.shortcut, value.locale, value.frequency.toString(), "0")))
        }
        store.record(borrowed, ops)
        changes += borrowed.size + ops.size
        val keys = inserts.associateBy { it.id }
        val returned = mutableListOf<SyncOperation>()
        for (op in ops) {
            currentCoroutineContext().ensureActive()
            val saved = call(op, keys.getValue(op.keyId))
            if (saved.returnedId == null || saved.returnedId <= 0) {
                // Settle the rows this batch did create before stopping at the unknown one.
                reconcile(returned, keys, plan)
                unknown(saved, keys.getValue(op.keyId))
            }
            returned += saved
        }
        reconcile(returned, keys, plan)
    }
    /** The returned ID is journalled before any readback, suspension or logging. */
    private suspend fun call(flight: SyncOperation, key: SyncKey): SyncOperation {
        val saved = flight.copy(phase = OperationPhase.RETURNED, returnedId = gateway.insert(intended(flight, key)))
        store.operation(saved)
        return saved
    }
    internal suspend fun audit(key: SyncKey, plan: SyncPlan) = audit(listOf(key), plan)
    /**
     * Audits bindings in batches: one access check, one ID query for OWNED rows and one word query for BORROWED rows.
     * Without [failed], the first failure stops the audit; with it, each key's failure is reported and the rest continue.
     */
    internal suspend fun audit(keys: Collection<SyncKey>, plan: SyncPlan, failed: (suspend (SyncKey, SyncStopped) -> Unit)? = null) {
        for (chunk in keys.distinctBy { it.id }.chunked(batch)) {
            access(plan)
            val bindings = store.bindings(chunk.map { it.id }).associateBy { it.keyId }
            fun usable(binding: ProviderBinding?) = binding != null && binding.conflict == null && binding.installationId == installationId
            val owned = chunk.mapNotNull { key -> bindings[key.id]?.takeIf { usable(it) && it.ownership == Ownership.OWNED }?.observed?.let(SyncCodec::row) }
            val rows = gateway.byIds(owned.map { it.id })
            val found = find(chunk.filter { key -> bindings[key.id]?.let { usable(it) && it.ownership == Ownership.BORROWED } == true }, plan)
            for (key in chunk) {
                try {
                    val binding = bindings[key.id] ?: continue
                    binding.conflict?.let { throw SyncStopped(it) }
                    if (binding.installationId != installationId) throw SyncStopped("INSTALLATION_MISMATCH")
                    when (binding.ownership) {
                        Ownership.OWNED -> {
                            val row = binding.observed?.let(SyncCodec::row) ?: throw SyncStopped("OWNERSHIP_UNKNOWN")
                            val actual = rows[row.id]
                            if (actual != row) conflict(key, binding, if (actual == null) "EXTERNAL_DELETE" else "EXTERNAL_EDIT")
                        }
                        // Contents are only an existing match; never elevate to OWNED, even after a DB loss.
                        Ownership.BORROWED -> if (found.getValue(key.id).isEmpty()) conflict(key, binding, "BORROWED_LOST")
                        Ownership.RELEASED -> Unit // A verified deletion is explained by its Journal; no conflict is cleared.
                        Ownership.UNKNOWN -> throw SyncStopped("OWNERSHIP_UNKNOWN")
                    }
                } catch (e: SyncStopped) {
                    failed?.invoke(key, e) ?: throw e
                }
            }
        }
    }
    private suspend fun conflict(key: SyncKey, binding: ProviderBinding, reason: String): Nothing {
        store.binding(binding.copy(ownership = if (binding.ownership == Ownership.OWNED) Ownership.RELEASED else binding.ownership,
            conflict = reason, observedAt = now()))
        throw SyncStopped(reason)
    }
    internal suspend fun insert(op: SyncOperation, key: SyncKey, plan: SyncPlan) {
        boundary(plan)
        // Recheck at the boundary: an external insertion after planning becomes BORROWED.
        val rows = find(key, plan)
        if (rows.isNotEmpty()) {
            store.verified(ProviderBinding(key.id, installationId, ownership = Ownership.BORROWED, observed = SyncCodec.row(rows.first()), observedAt = now()),
                op.copy(phase = OperationPhase.VERIFIED, error = EXISTING_MATCH))
            return
        }
        val flight = op.copy(phase = OperationPhase.IN_FLIGHT, attempts = op.attempts + 1)
        store.operation(flight)
        changes++
        val saved = call(flight, key)
        if (saved.returnedId == null || saved.returnedId <= 0) unknown(saved, key)
        reconcile(saved, key, plan)
    }
    internal fun intended(op: SyncOperation, key: SyncKey): me.midra.dictionarysync.probe.ProbeValue {
        val fields = op.expected?.let(SyncCodec::unpack) ?: throw SyncStopped("INSERT_INTENT_MISSING")
        if (fields.size != 6 || fields[0] != ABSENT || fields[5] != "0") throw SyncStopped("INSERT_INTENT_INVALID")
        val value = me.midra.dictionarysync.probe.ProbeValue(fields[1]!!, fields[2], fields[3], fields[4]!!.toInt())
        if (!key.matches(value.row(0))) throw SyncStopped("INSERT_INTENT_INVALID")
        return value
    }
    private suspend fun unknown(op: SyncOperation, key: SyncKey): Nothing {
        store.verified(ProviderBinding(key.id, installationId, ownership = Ownership.UNKNOWN, observed = null, observedAt = now(), conflict = "INSERT_UNKNOWN"),
            op.copy(phase = OperationPhase.AMBIGUOUS, error = "INSERT_UNKNOWN"))
        throw SyncStopped("INSERT_UNKNOWN")
    }
    /**
     * Deletes audited OWNED rows. Each call is journalled IN_FLIGHT beforehand; the results are journalled together,
     * since an IN_FLIGHT delete is settled by reading its row back (absent: done; unchanged: not yet deleted).
     */
    internal suspend fun delete(targets: List<Pair<SyncKey, ProviderBinding>>, plan: SyncPlan) {
        if (targets.isEmpty()) return
        val ops = targets.map { (key, binding) ->
            SyncOperation(UUID.randomUUID().toString(), plan.id, key.id, OperationKind.DELETE, phase = OperationPhase.IN_FLIGHT, attempts = 1, expected = binding.observed)
        }
        store.record(emptyList(), ops)
        changes += ops.size
        val returned = mutableListOf<SyncOperation>()
        try {
            for (op in ops) {
                currentCoroutineContext().ensureActive()
                val expected = op.expected?.let(SyncCodec::row) ?: throw SyncStopped("OWNERSHIP_UNKNOWN")
                val count = gateway.delete(expected)
                returned += op.copy(phase = OperationPhase.RETURNED, result = count)
                if (count != 1) break // Reconciled below as DELETE_ZERO or DELETE_MULTIPLE.
            }
        } finally {
            store.record(emptyList(), returned)
        }
        reconcile(returned, targets.associate { it.first.id to it.first }, plan)
    }
    private suspend fun delete(op: SyncOperation, key: SyncKey, plan: SyncPlan) {
        boundary(plan)
        val expected = op.expected?.let(SyncCodec::row) ?: throw SyncStopped("OWNERSHIP_UNKNOWN")
        if (gateway.byId(expected.id) != expected) {
            val binding = requireNotNull(store.binding(key.id))
            conflict(key, binding, "EXTERNAL_EDIT_OR_DELETE")
        }
        val flight = op.copy(phase = OperationPhase.IN_FLIGHT, attempts = op.attempts + 1)
        store.operation(flight)
        changes++
        val count = gateway.delete(expected)
        val saved = flight.copy(phase = OperationPhase.RETURNED, result = count)
        store.operation(saved)
        reconcile(saved, key, plan)
    }
    internal suspend fun reconcile(op: SyncOperation, key: SyncKey, plan: SyncPlan) = reconcile(listOf(op), mapOf(key.id to key), plan)
    /** Settles journalled operations against the Provider, reading rows back in batches. Never repeats a call blindly. */
    internal suspend fun reconcile(ops: List<SyncOperation>, keys: Map<String, SyncKey>, plan: SyncPlan) {
        for (chunk in ops.chunked(batch)) {
            boundary(plan)
            val existing = mutableListOf<SyncKey>()
            val readback = mutableListOf<SyncOperation>()
            val unsent = mutableListOf<SyncOperation>()
            for (op in chunk) {
                val key = keys.getValue(op.keyId)
                when {
                    op.kind != OperationKind.INSERT -> readback += op
                    op.phase == OperationPhase.PREPARED -> insert(op, key, plan)
                    op.error == EXISTING_MATCH -> existing += key
                    op.returnedId != null -> readback += op
                    op.phase == OperationPhase.IN_FLIGHT -> unsent += op
                    else -> unknown(op, key) // Never infer ownership or blindly issue another insert.
                }
            }
            audit(existing, plan)
            // No ID was returned. Absent rows prove the call never took effect, so it is retried after a fresh search;
            // any matching row may be ours or someone else's and is never adopted.
            val found = find(unsent.map { keys.getValue(it.keyId) }, plan)
            for (op in unsent) {
                val key = keys.getValue(op.keyId)
                if (found.getValue(key.id).isNotEmpty()) unknown(op, key)
                insert(op.copy(phase = OperationPhase.PREPARED), key, plan)
            }
            val expected = readback.associate { op ->
                op.id to if (op.kind == OperationKind.INSERT) intended(op, keys.getValue(op.keyId)).row(op.returnedId!!)
                else {
                    // 0 and >1 are evidence of an incompatible predicate or a race; do not call either success.
                    if (op.result != null && op.result != 1) throw SyncStopped(if (op.result == 0) "DELETE_ZERO" else "DELETE_MULTIPLE")
                    op.expected?.let(SyncCodec::row) ?: throw SyncStopped("OWNERSHIP_UNKNOWN")
                }
            }
            val rows = gateway.byIds(expected.values.map { it.id })
            val bindings = store.bindings(readback.map { it.keyId }).associateBy { it.keyId }
            val verified = mutableListOf<Pair<ProviderBinding, SyncOperation>>()
            try {
                for (op in readback) {
                    val key = keys.getValue(op.keyId)
                    val wanted = expected.getValue(op.id)
                    val actual = rows[wanted.id]
                    if (op.kind == OperationKind.INSERT) {
                        if (actual != wanted) {
                            val binding = bindings[key.id]
                                ?: ProviderBinding(key.id, installationId, ownership = Ownership.UNKNOWN, observed = null, observedAt = now())
                            conflict(key, binding, "INSERT_READBACK_MISMATCH")
                        }
                        val binding = bindings[key.id]
                        binding?.conflict?.let { throw SyncStopped(it) }
                        val owned = ProviderBinding(key.id, installationId, ownership = Ownership.OWNED, observed = SyncCodec.row(actual), observedAt = now())
                        // An already verified insert that still reads back the same needs no new record.
                        if (op.phase != OperationPhase.VERIFIED || binding?.copy(observedAt = owned.observedAt) != owned)
                            verified += owned to op.copy(phase = OperationPhase.VERIFIED)
                    } else if (actual == null && op.phase != OperationPhase.PREPARED) {
                        val binding = requireNotNull(bindings[key.id])
                        if (op.phase != OperationPhase.VERIFIED || binding.ownership != Ownership.RELEASED)
                            verified += binding.copy(ownership = Ownership.RELEASED, observedAt = now()) to op.copy(phase = OperationPhase.VERIFIED)
                    } else if (actual == wanted && op.phase != OperationPhase.VERIFIED && op.result == null) {
                        delete(op, key, plan) // Only a proven unchanged ID, after an unknown result.
                    } else throw SyncStopped("DELETE_READBACK_MISMATCH")
                }
            } finally {
                store.record(verified.map { it.first }, verified.map { it.second })
            }
        }
    }
    private fun recovery() = SyncRecoveryEngine(store, gateway, installationId, this, now)
    suspend fun prepareRecovery(id: String, purpose: PlanPurpose): SyncPreview = recovery().prepare(id, purpose)
    companion object {
        /** The insert intent's marker that the key had no row when planned. */
        private const val ABSENT = "ABSENT"
        /** An insert that found an existing match and borrowed it instead. */
        internal const val EXISTING_MATCH = "EXISTING_MATCH"
        /** A key with no binding, or one the app no longer holds, is inserted (or borrowed) when wanted. */
        internal fun needsInsert(binding: ProviderBinding?) = binding == null || binding.ownership == Ownership.RELEASED
        /** Below SQLite's 999 bound-variable limit, so a batch is one IN query on older Android versions. */
        const val BATCH = 900
        /** Managed shared-dictionary rows, including the old/new union while a source is replaced. */
        const val KEY_LIMIT = 200_000
    }
}
