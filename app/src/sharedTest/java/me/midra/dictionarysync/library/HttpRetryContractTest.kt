package me.midra.dictionarysync.library

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLHandshakeException
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.data.source.HttpRetry
import org.junit.Assert.*
import org.junit.Test

class HttpRetryContractTest {
    private val now = Instant.parse("2026-10-29T00:00:00Z").toEpochMilli()
    @Test fun retryAfterSecondsDatesPastAndAllLegacyHttpDateForms() {
        assertEquals(now + 120_000, HttpRetry.retryAfter("120", now))
        assertEquals(now, HttpRetry.retryAfter("0", now))
        assertEquals(now + 60_000, HttpRetry.retryAfter("Thu, 29 Oct 2026 00:01:00 GMT", now))
        assertEquals(now + 60_000, HttpRetry.retryAfter("Thursday, 29-Oct-26 00:01:00 GMT", now))
        assertEquals(now + 60_000, HttpRetry.retryAfter("Thu Oct 29 00:01:00 2026", now))
        assertEquals(Instant.parse("2076-01-01T00:00:00Z").toEpochMilli(), HttpRetry.retryAfter("Wednesday, 01-Jan-76 00:00:00 GMT", now))
        assertEquals(now, HttpRetry.retryAfter("Saturday, 01-Jan-77 00:00:00 GMT", now))
        assertEquals(now, HttpRetry.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT", now))
    }
    @Test fun invalidRetryAfterUsesBackoffAndHugeNumbersNeverWrap() {
        for (raw in listOf("-1", "+1", "1.5", "one", "120, 120", "Thu, 99 Oct 2026 00:01:00 GMT", "120\r\nX: y", "")) assertNull(raw, HttpRetry.retryAfter(raw, now))
        assertEquals(Long.MAX_VALUE, HttpRetry.retryAfter("999999999999999999999999", now))
        assertEquals(Long.MAX_VALUE, HttpRetry.retryAfter(Long.MAX_VALUE.toString(), now))
        assertEquals(Long.MAX_VALUE, HttpRetry.add(Long.MAX_VALUE - 1, 30_000))
    }
    @Test fun transientHttpAndNetworkFailuresRetryButTlsFormatAndPermissionFailuresStop() {
        for (status in listOf(408, 429, 500, 502, 503, 504, 599)) assertTrue(HttpRetry.retryable(DictionaryProblem("HTTP_$status")))
        for (status in listOf(204, 206, 304, 400, 401, 403, 404, 410)) assertFalse(HttpRetry.retryable(DictionaryProblem("HTTP_$status")))
        assertTrue(HttpRetry.retryable(SocketTimeoutException())); assertTrue(HttpRetry.retryable(UnknownHostException()))
        assertTrue(HttpRetry.retryable(DictionaryProblem("TOTAL_TIMEOUT"))); assertTrue(HttpRetry.retryable(DictionaryProblem("INCOMPLETE_RESPONSE")))
        assertFalse(HttpRetry.retryable(SSLHandshakeException("synthetic")))
        assertFalse(HttpRetry.retryable(DictionaryProblem("NOT_DICTIONARY")))
        assertFalse(HttpRetry.retryable(SecurityException()))
    }
    @Test fun fiveExponentialRetriesHaveIndependentBudget() {
        assertEquals(5, HttpRetry.MAX_RETRIES)
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L), (1..5).map(HttpRetry::backoff))
    }
}
