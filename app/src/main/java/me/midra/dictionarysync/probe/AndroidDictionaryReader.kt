package me.midra.dictionarysync.probe

import android.content.ContentResolver
import android.provider.UserDictionary.Words

class NullCursorException : IllegalStateException()
class MissingColumnException : IllegalStateException()

class AndroidDictionaryReader(private val resolver: ContentResolver) : DictionaryReader {
    override fun query(marker: String): QuerySummary {
        // Full scan only for this explicit compatibility diagnostic. Do not log or persist personal rows.
        val cursor = resolver.query(Words.CONTENT_URI, COLUMNS, null, null, null)
            ?: throw NullCursorException()
        return cursor.use {
            val indices = COLUMNS.map { name ->
                it.getColumnIndex(name).also { index -> if (index < 0) throw MissingColumnException() }
            }
            var count = 0
            val matches = mutableListOf<ProbeRow>()
            while (it.moveToNext()) {
                count++
                if (it.getString(indices[1]) == marker && matches.size < 2) {
                    fun nullableString(index: Int) =
                        if (it.isNull(index)) null else it.getString(index)
                    fun nullableInt(index: Int) =
                        if (it.isNull(index)) null else it.getInt(index)
                    matches += ProbeRow(
                        id = it.getLong(indices[0]),
                        word = marker,
                        shortcut = nullableString(indices[2]),
                        locale = nullableString(indices[3]),
                        frequency = nullableInt(indices[4]),
                        appId = nullableInt(indices[5]),
                    )
                }
            }
            QuerySummary(count, matches)
        }
    }

    companion object {
        val COLUMNS = arrayOf(
            Words._ID, Words.WORD, Words.SHORTCUT, Words.LOCALE, Words.FREQUENCY, Words.APP_ID,
        )
    }
}
