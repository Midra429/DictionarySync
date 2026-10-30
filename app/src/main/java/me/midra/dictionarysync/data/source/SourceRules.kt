package me.midra.dictionarysync.data.source

import java.net.URI
import java.util.Locale
import me.midra.dictionarysync.data.parser.DictionaryProblem

object SourceRules {
    const val MAX_SOURCES = 20
    fun url(value: String): URI {
        val text = value.trim().substringBefore('#')
        val uri = try { URI(text) } catch (_: Exception) { throw DictionaryProblem("INVALID_URL") }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.port !in -1..65535 || uri.port == 0) throw DictionaryProblem("HTTPS_URL_REQUIRED")
        return uri // Never rebuild, sort, decode or append the raw query.
    }
    fun locale(value: String): String {
        if (value.trim().isEmpty() || value == "AUTO") return "AUTO"
        if (value == "ALL") return value
        val locale = try { Locale.Builder().setLanguageTag(value).build() } catch (_: Exception) { throw DictionaryProblem("INVALID_LOCALE") }
        if (locale.language.isEmpty() || locale.language == "und") throw DictionaryProblem("INVALID_LOCALE")
        return locale.toLanguageTag()
    }
    fun name(value: String) {
        if (value.isBlank()) throw DictionaryProblem("INVALID_NAME")
    }
    fun resolvedLocale(value: String) = if (value == "AUTO") "ja" else locale(value)
    /** An entry's language: under AUTO the one its file declares, otherwise the source's resolution (01). A manual choice always wins. */
    fun entryLocale(value: String, declared: String?) = if (value == "AUTO" && declared != null) declared else resolvedLocale(value)
    val intervals = listOf(1, 6, 12, 24, 72, 168)
    fun like(value: String) = "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
