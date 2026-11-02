package me.midra.dictionarysync.sync

import me.midra.dictionarysync.data.local.SyncState

/*
 * States of plans, bindings and journalled Provider operations. Room stores each by its name, so the names are part of
 * the schema: never rename a constant, only add new ones.
 */

enum class PlanState {
    PREPARED, ADDING, REMOVING,
    /** Stopped on an error; only an explicit recovery continues it. */
    RECOVERY_REQUIRED,
    /** The parent of a running recovery plan. */
    RECOVERING,
    COMMITTED, CANCELLED,
    /** Closed by a recovery that kept the previous generation. */
    FAILED_PRESERVED;

    val terminal get() = this == COMMITTED || this == CANCELLED || this == FAILED_PRESERVED

    /**
     * What the source shows while it has this plan. Null for [COMMITTED]: a commit decides the source's state itself
     * (synced, removed, or a newer generation pending), which the plan's state alone cannot tell.
     */
    val sourceState: SyncState? get() = when (this) {
        PREPARED -> SyncState.PREPARED
        ADDING -> SyncState.ADDING
        REMOVING -> SyncState.REMOVING
        RECOVERY_REQUIRED -> SyncState.RECOVERY_REQUIRED
        RECOVERING -> SyncState.RECOVERING
        CANCELLED -> SyncState.CANCELLED
        FAILED_PRESERVED -> SyncState.FAILED_PRESERVED
        COMMITTED -> null
    }

    companion object {
        val TERMINAL = entries.filter { it.terminal }
    }
}

enum class PlanPurpose {
    /** An ordinary sync of one source's latest generation (or its removal). */
    APPLY,
    /** Recovery: finish the interrupted generation. */
    RESYNC,
    /** Recovery: restore the previous generation. */
    COMPENSATE,
    /** Recovery: remove the source and release its words. */
    REMOVE_SOURCE;

    val recovery get() = this != APPLY
}

/** How the app relates to the Provider row of a key. */
enum class Ownership {
    /** Inserted by this installation and read back unchanged; the only rows the app ever deletes. */
    OWNED,
    /** An existing matching row the app reuses but never modifies. */
    BORROWED,
    /** No longer held: deleted by the app, released by every source, or edited externally. */
    RELEASED,
    /** An insert whose effect could not be determined. */
    UNKNOWN,
}

enum class OperationKind { INSERT, DELETE }

enum class OperationPhase {
    PREPARED,
    /** Recorded before the Provider call; the call may or may not have taken effect. */
    IN_FLIGHT,
    /** The call returned; its result is recorded but not yet read back. */
    RETURNED,
    VERIFIED,
    /** An insert whose effect cannot be determined. */
    AMBIGUOUS,
}
