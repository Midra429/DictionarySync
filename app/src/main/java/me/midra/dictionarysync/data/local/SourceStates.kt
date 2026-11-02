package me.midra.dictionarysync.data.local

/*
 * States of a source. Room stores each by its name, so the names are part of the schema: never rename a constant,
 * only add new ones.
 */

/** Whether a source is listed. A deleted source keeps its row for ownership and recovery history. */
enum class SourceLifecycle { ACTIVE, DELETE_PENDING, DELETED }

/** The last fetch of a source's current settings. Independent of the shared dictionary. */
enum class FetchState {
    NOT_FETCHED,
    /** URL, format, language or encoding changed; the old generation stays until a fetch with the new ones succeeds. */
    SETTINGS_CHANGED,
    FETCHING,
    FETCHED,
    FETCH_RETRY,
    FETCH_FAILED,
    NETWORK_WAIT,
    /** A fetch that was running when the process ended. */
    INTERRUPTED,
}

/**
 * The shared-dictionary state of a source. While a plan runs it mirrors the plan's state ([PREPARED] to [CANCELLED]).
 */
enum class SyncState {
    NOT_SYNCED,
    /** The latest generation is waiting for the apply chain. */
    UPDATE_PENDING,
    /** A disabled or deleted source is waiting for the apply chain to release its words. */
    REMOVE_PENDING,
    PREPARED, ADDING, REMOVING,
    RECOVERY_REQUIRED, RECOVERING, FAILED_PRESERVED, CANCELLED,
    SYNCED,
    /** Nothing of this source is in the shared dictionary. */
    REMOVED,
    SYNC_FAILED;

    /** Work the apply chain picks up by itself: desired changes, and an ordinary plan interrupted mid-apply. */
    val dueInBackground get() = this in setOf(UPDATE_PENDING, REMOVE_PENDING, PREPARED, ADDING, REMOVING)
    /** A desired change that has not started; an interrupted plan goes first, since it blocks every other source. */
    val waiting get() = this == UPDATE_PENDING || this == REMOVE_PENDING
}
