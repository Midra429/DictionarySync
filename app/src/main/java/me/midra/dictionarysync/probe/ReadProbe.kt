package me.midra.dictionarysync.probe

/** Deliberately read-only: a visible row is evidence of reading, never of safe CRUD or IME support. */
interface DictionaryReader {
    fun query(marker: String): QuerySummary
}

data class ProbeRow(
    val id: Long,
    val word: String,
    val shortcut: String?,
    val locale: String?,
    val frequency: Int?,
    val appId: Int?,
)

data class QuerySummary(val rowCount: Int, val markerRows: List<ProbeRow>)

enum class ReadVerdict {
    READ_CONFIRMED,
    EMPTY_UNVERIFIED,
    MARKER_NOT_VISIBLE,
    AMBIGUOUS_MARKER,
}

class ReadProbe(private val reader: DictionaryReader) {
    fun run(marker: String): Pair<QuerySummary, ReadVerdict> {
        require(MARKER_PATTERN.matches(marker))
        val summary = reader.query(marker)
        require(summary.rowCount >= summary.markerRows.size)
        // Do not trust a fake or misbehaving provider to apply the requested filter.
        require(summary.markerRows.all { it.word == marker })
        val verdict = when {
            summary.rowCount == 0 -> ReadVerdict.EMPTY_UNVERIFIED
            summary.markerRows.isEmpty() -> ReadVerdict.MARKER_NOT_VISIBLE
            summary.markerRows.size != 1 -> ReadVerdict.AMBIGUOUS_MARKER
            else -> ReadVerdict.READ_CONFIRMED
        }
        return summary to verdict
    }

    companion object {
        /** The user registers this word by hand, so it names the app; lowercase ASCII survives every settings screen unchanged. */
        const val MARKER_PREFIX = "dictionarysync_check_"
        /** Temporary row of the write check, paired with the marker by its suffix. */
        const val TRIAL_PREFIX = "dictionarysync_test_"
        val MARKER_PATTERN = Regex("${MARKER_PREFIX}[0-9a-f]{8}")
    }
}
