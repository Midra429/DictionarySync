package me.midra.dictionarysync.data.local

import androidx.room.TypeConverter
import me.midra.dictionarysync.sync.FetchOutcome
import me.midra.dictionarysync.sync.OperationKind
import me.midra.dictionarysync.sync.OperationPhase
import me.midra.dictionarysync.sync.Ownership
import me.midra.dictionarysync.sync.PlanPurpose
import me.midra.dictionarysync.sync.PlanState
import me.midra.dictionarysync.sync.WaitReason

/** The constant named [name], or null if this build does not know it. */
inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }

/**
 * Stores the state enums by name, like Room's built-in conversion, but reads a name this build does not know (one
 * written by a newer build, before a downgrade) as a cautious fallback instead of failing every query of the table.
 * Each fallback stops automatic work rather than starting it: nothing unknown is deleted, retried or reported as done.
 */
class StateConverters {
    @TypeConverter fun name(value: SourceLifecycle) = value.name
    /** Kept listed, so the user still sees and can delete it. */
    @TypeConverter fun sourceLifecycle(name: String) = enumOrNull(name) ?: SourceLifecycle.ACTIVE

    @TypeConverter fun name(value: FetchState) = value.name
    /** Shown as an interrupted fetch, which the user can run again. */
    @TypeConverter fun fetchState(name: String) = enumOrNull(name) ?: FetchState.INTERRUPTED

    @TypeConverter fun name(value: SyncState) = value.name
    /** Not picked up by the apply chain; the user can retry it. */
    @TypeConverter fun syncState(name: String) = enumOrNull(name) ?: SyncState.SYNC_FAILED

    @TypeConverter fun name(value: PlanState) = value.name
    /** Unfinished and waiting for the user, which also holds back every other Provider write. */
    @TypeConverter fun planState(name: String) = enumOrNull(name) ?: PlanState.RECOVERY_REQUIRED

    @TypeConverter fun name(value: PlanPurpose) = value.name
    /** An ordinary plan, whose recovery the user chooses explicitly. */
    @TypeConverter fun planPurpose(name: String) = enumOrNull(name) ?: PlanPurpose.APPLY

    @TypeConverter fun name(value: Ownership) = value.name
    /** Never deleted; a sync involving it stops with OWNERSHIP_UNKNOWN. */
    @TypeConverter fun ownership(name: String) = enumOrNull(name) ?: Ownership.UNKNOWN

    @TypeConverter fun name(value: OperationKind) = value.name
    /** An insert's journalled intent is validated before any use, so an unknown kind stops instead of deleting. */
    @TypeConverter fun operationKind(name: String) = enumOrNull(name) ?: OperationKind.INSERT

    @TypeConverter fun name(value: OperationPhase) = value.name
    /** Settled by reading the row back, never by repeating the call blindly. */
    @TypeConverter fun operationPhase(name: String) = enumOrNull(name) ?: OperationPhase.AMBIGUOUS

    @TypeConverter fun name(value: FetchOutcome) = value.name
    @TypeConverter fun fetchOutcome(name: String) = enumOrNull(name) ?: FetchOutcome.FETCH_FAILED

    @TypeConverter fun name(value: WaitReason) = value.name
    /** Waits for the network, which consumes no retry budget. */
    @TypeConverter fun waitReason(name: String) = enumOrNull(name) ?: WaitReason.NETWORK_WAIT
}
