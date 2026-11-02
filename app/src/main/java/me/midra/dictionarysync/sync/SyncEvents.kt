package me.midra.dictionarysync.sync

import me.midra.dictionarysync.data.local.SourceEntity

/** What background fetch and apply passes report as they go. Workers notify the user; every other caller ignores it. */
interface SyncEvents {
    fun fetching(source: SourceEntity) {}
    fun applying(source: SourceEntity) {}
    /** The pass ended without another one queued right behind it. */
    fun idle() {}
    /** A successful apply and the words it inserted into and deleted from the shared dictionary. */
    fun applied(source: SourceEntity, added: Int, removed: Int) {}
    /** A fetch that ended for good, after its automatic retries. */
    fun fetchFailed(source: SourceEntity, code: String) {}
    fun applyFailed(source: SourceEntity, code: String) {}

    companion object { val None = object : SyncEvents {} }
}
