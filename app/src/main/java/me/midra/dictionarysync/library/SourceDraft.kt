package me.midra.dictionarysync.library

import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.data.parser.DictionaryParser
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.data.source.SourceRules

/** What the add/edit form holds; nothing is stored until a fetch with it succeeds. */
data class SourceDraft(
    val name: String,
    val url: String,
    val locale: String = "AUTO",
    val format: String = "AUTO",
    val encoding: String = "AUTO",
    val syncIntervalHours: Int = 24,
) {
    /** The normalized settings, or the first [DictionaryProblem] of the draft. */
    fun validated(): SourceSettings {
        SourceRules.name(name)
        val url = SourceRules.url(url).toString()
        val locale = SourceRules.locale(locale)
        if (syncIntervalHours !in SourceRules.intervals) throw DictionaryProblem("INVALID_INTERVAL")
        if (format !in DictionaryParser.formats || (encoding != "AUTO" && encoding !in DictionaryParser.ENCODINGS)) throw DictionaryProblem("UNSUPPORTED_FORMAT")
        return SourceSettings(name.trim(), url, locale, format, encoding, syncIntervalHours)
    }
}

/** Validated settings of a source. [url] keeps its query exactly; only the fragment and outer whitespace are removed. */
data class SourceSettings(val name: String, val url: String, val locale: String, val format: String, val encoding: String,
    val syncIntervalHours: Int) {
    /** Whether a source with these settings needs a new fetch: anything but the name and interval changed. */
    fun changesFetch(source: SourceEntity) =
        source.url != url || source.locale != locale || source.format != format || source.encoding != encoding
}
