package me.midra.dictionarysync.library

import me.midra.dictionarysync.data.source.MemoryBudget
import org.junit.Assert.*
import org.junit.Test

class MemoryBudgetTest {
    private val mib = 1024L * 1024

    @Test fun limitsGrowWithTheHeapInsteadOfFixedCounts() {
        val small = MemoryBudget(128 * mib)
        val large = MemoryBudget(512 * mib)
        assertTrue(large.maxBytes > small.maxBytes)
        assertTrue(large.maxKeys > small.maxKeys)
        assertTrue(large.maxEntries(0) > small.maxEntries(0))
    }

    @Test fun aBodyLeavesLessRoomForItsEntries() {
        val budget = MemoryBudget(256 * mib)
        assertTrue(budget.maxEntries(budget.maxBytes / 2) < budget.maxEntries(0))
        assertEquals(0L, budget.maxEntries(budget.maxBytes))
    }

    @Test fun aHeapBelowTheReserveAllowsNothing() {
        val budget = MemoryBudget(MemoryBudget.RESERVED / 2)
        assertEquals(0L, budget.maxBytes)
        assertEquals(0L, budget.maxKeys)
        assertEquals(0L, budget.maxEntries(0))
    }

    @Test fun heapForGivesExactlyTheRequestedRoom() {
        for (usable in listOf(0L, 1L, 2L, 3L, 1_000_003L, 123_456_789L)) {
            assertEquals(usable / MemoryBudget.PER_KEY, MemoryBudget(MemoryBudget.heapFor(usable)).maxKeys)
            assertEquals(usable / MemoryBudget.PER_BYTE, MemoryBudget(MemoryBudget.heapFor(usable)).maxBytes)
        }
    }

    @Test fun typicalHeapsAllowMoreThanTheFormerFixedLimits() {
        // A largeHeap of 512 MiB: the former limits were 20 MiB per body and 200,000 managed keys.
        val large = MemoryBudget(512 * mib)
        assertTrue(large.maxBytes > 20 * mib)
        assertTrue(large.maxKeys > 200_000)
    }
}
