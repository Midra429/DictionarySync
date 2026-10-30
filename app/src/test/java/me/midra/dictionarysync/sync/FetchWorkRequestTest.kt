package me.midra.dictionarysync.sync

import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.*
import org.junit.Test

class FetchWorkRequestTest {
    @Test fun userRequestedFetchIsExpeditedOnlyWhereNoForegroundNotificationIsNeeded() {
        val now = FetchWork.request(expedited = true, sdk = 31).workSpec
        assertTrue(now.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, now.outOfQuotaPolicy)
        assertFalse(FetchWork.request(expedited = true, sdk = 30).workSpec.expedited)
        assertFalse(FetchWork.request(sdk = 36).workSpec.expedited)
    }

    @Test fun expeditedRequestKeepsTheCoarseNetworkAndStorageConstraints() {
        val spec = FetchWork.request(expedited = true, sdk = 36).workSpec
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertTrue(spec.constraints.requiresStorageNotLow())
    }
}
