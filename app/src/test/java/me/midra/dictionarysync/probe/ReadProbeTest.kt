package me.midra.dictionarysync.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadProbeTest {
    private val marker = "dictionarysync_check_a1b2c3d4"
    private val row = ProbeRow(10, marker, null, "ja", 128, 0)
    private fun probe(summary: QuerySummary) = ReadProbe(object : DictionaryReader {
        override fun query(marker: String) = summary
    })

    @Test fun emptyCursorDoesNotProveReadAccess() {
        assertEquals(ReadVerdict.EMPTY_UNVERIFIED, probe(QuerySummary(0, emptyList())).run(marker).second)
    }

    @Test fun unrelatedVisibleRowsDoNotProveMarkerVisibility() {
        assertEquals(ReadVerdict.MARKER_NOT_VISIBLE, probe(QuerySummary(15, emptyList())).run(marker).second)
    }

    @Test fun exactMarkerWithZeroAppIdOnlyConfirmsReading() {
        assertEquals(ReadVerdict.READ_CONFIRMED, probe(QuerySummary(15, listOf(row))).run(marker).second)
    }

    @Test fun duplicateMarkerIsAmbiguous() {
        assertEquals(ReadVerdict.AMBIGUOUS_MARKER,
            probe(QuerySummary(15, listOf(row, row.copy(id = 11)))).run(marker).second)
    }

    @Test fun providerFailureIsNotConvertedToEmptySuccess() {
        val failing = ReadProbe(object : DictionaryReader {
            override fun query(marker: String): QuerySummary = throw SecurityException()
        })
        assertThrows(SecurityException::class.java) { failing.run(marker) }
    }

    @Test fun arbitraryPersonalWordCannotBeUsedAsMarker() {
        assertThrows(IllegalArgumentException::class.java) {
            probe(QuerySummary(0, emptyList())).run("personal word")
        }
    }

    @Test fun mismatchedReturnedMarkerIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            probe(QuerySummary(1, listOf(row.copy(word = "different")))).run(marker)
        }
    }
}
