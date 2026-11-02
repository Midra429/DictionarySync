package me.midra.dictionarysync.data.source

/**
 * Size limits derived from the heap this process may use, instead of fixed counts. Neither the shared dictionary
 * Provider nor the parsers cap a dictionary; what does is memory: a fetch holds the body and its parsed entries at once,
 * and planning, applying and recovering hold every key of the sources involved. Each check refuses, with the same codes
 * the fixed limits used, before the work would need more heap than the device gives the app.
 *
 * The per-unit costs bound the smallest heap that ran each stage on the JVM, in every supported format (docs/04).
 * [MARGIN_PERCENT] covers ART's different object layout and collector; it is an assumption until measured on devices.
 */
class MemoryBudget(maxHeap: Long = Runtime.getRuntime().maxMemory()) {
    private val usable = ((maxHeap - RESERVED).coerceAtLeast(0) / MARGIN_PERCENT) * 100 +
        ((maxHeap - RESERVED).coerceAtLeast(0) % MARGIN_PERCENT) * 100 / MARGIN_PERCENT

    /** The largest body (after decompression) a fetch may hold. */
    val maxBytes: Long = usable / PER_BYTE

    /** The most entries a body of [bytes] may parse into. */
    fun maxEntries(bytes: Long): Long = ((usable - bytes * PER_BYTE) / PER_ENTRY).coerceAtLeast(0)

    /** The most shared-dictionary keys (or Provider rows) one operation may hold. */
    val maxKeys: Long = usable / PER_KEY

    companion object {
        /** Heap kept for the UI, Room, WorkManager and the code itself. */
        const val RESERVED = 48L * 1024 * 1024
        /** The heap actually assumed per measured byte, in percent. */
        const val MARGIN_PERCENT = 150L
        /** Heap per body byte while it is decoded, split into lines and parsed. */
        const val PER_BYTE = 8L
        /** Heap per parsed entry, beyond its share of the body. */
        const val PER_ENTRY = 300L
        /** Heap per key while a plan is prepared, applied or recovered. */
        const val PER_KEY = 700L

        /** The smallest heap whose budget has [usable] bytes to spend, for tests and documentation. */
        fun heapFor(usable: Long): Long = RESERVED + (usable * MARGIN_PERCENT + 99) / 100
    }
}
