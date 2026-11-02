package me.midra.dictionarysync.data

import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.sync.*
import org.junit.Assert.*
import org.junit.Test

/** Stored state names: every known name round-trips; an unknown one (from a newer build) reads as a cautious fallback. */
class StateConvertersTest {
    private val c = StateConverters()

    @Test fun everyKnownNameRoundTrips() {
        SourceLifecycle.entries.forEach { assertEquals(it, c.sourceLifecycle(c.name(it))) }
        FetchState.entries.forEach { assertEquals(it, c.fetchState(c.name(it))) }
        SyncState.entries.forEach { assertEquals(it, c.syncState(c.name(it))) }
        PlanState.entries.forEach { assertEquals(it, c.planState(c.name(it))) }
        PlanPurpose.entries.forEach { assertEquals(it, c.planPurpose(c.name(it))) }
        Ownership.entries.forEach { assertEquals(it, c.ownership(c.name(it))) }
        OperationKind.entries.forEach { assertEquals(it, c.operationKind(c.name(it))) }
        OperationPhase.entries.forEach { assertEquals(it, c.operationPhase(c.name(it))) }
        FetchOutcome.entries.forEach { assertEquals(it, c.fetchOutcome(c.name(it))) }
        WaitReason.entries.forEach { assertEquals(it, c.waitReason(c.name(it))) }
    }

    @Test fun unknownNamesStopAutomaticWorkInsteadOfFailing() {
        val future = "ADDED_IN_A_LATER_BUILD"
        assertEquals(SourceLifecycle.ACTIVE, c.sourceLifecycle(future))
        assertFalse(c.syncState(future).dueInBackground) // The apply chain does not pick it up.
        assertFalse(c.planState(future).terminal) // An unknown plan waits for the user and blocks other writes.
        assertEquals(Ownership.UNKNOWN, c.ownership(future)) // Never deleted.
        assertEquals(OperationPhase.AMBIGUOUS, c.operationPhase(future))
        assertTrue(c.waitReason(future).network) // Waiting consumes no retry budget.
    }

    @Test fun aCommitLeavesTheSourceStateToTheCommit() {
        assertNull(PlanState.COMMITTED.sourceState)
        PlanState.entries.filter { it != PlanState.COMMITTED }.forEach { assertNotNull(it.sourceState) }
        assertEquals(PlanState.entries.filter { it.terminal }, PlanState.TERMINAL)
    }
}
