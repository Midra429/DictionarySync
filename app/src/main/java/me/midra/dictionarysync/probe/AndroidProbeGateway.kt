package me.midra.dictionarysync.probe

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.UserDictionary.Words
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import me.midra.dictionarysync.data.source.MemoryBudget

class AndroidProbeGateway(private val resolver: ContentResolver) : ProbeGateway, Closeable {
    private val notifications = AtomicInteger()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { notifications.incrementAndGet() }
    }
    init { resolver.registerContentObserver(Words.CONTENT_URI, true, observer) }
    override fun close() = resolver.unregisterContentObserver(observer)
    override fun notificationCount() = notifications.get()

    override fun snapshot(): List<ProbeRow> {
        val cursor = resolver.query(Words.CONTENT_URI, AndroidDictionaryReader.COLUMNS, null, null, null)
            ?: throw NullCursorException()
        return cursor.use {
            val indices = AndroidDictionaryReader.COLUMNS.map { column ->
                it.getColumnIndex(column).also { i -> if (i < 0) throw MissingColumnException() }
            }
            // The whole shared dictionary, including every word this app added, is read; only memory bounds it.
            val limit = MemoryBudget().maxKeys
            buildList {
                while (it.moveToNext()) {
                    if (size >= limit) throw ProbeStopped("Diagnostic scan limit exceeded")
                    fun string(i: Int) = if (it.isNull(i)) null else it.getString(i)
                    fun integer(i: Int) = if (it.isNull(i)) null else it.getInt(i)
                    add(ProbeRow(it.getLong(indices[0]), string(indices[1]) ?: throw MissingColumnException(),
                        string(indices[2]), string(indices[3]), integer(indices[4]), integer(indices[5])))
                }
            }
        }
    }

    override fun insert(value: ProbeValue): Long? {
        val values = ContentValues().apply {
            put(Words.WORD, value.word)
            put(Words.SHORTCUT, value.shortcut)
            put(Words.LOCALE, value.locale)
            put(Words.FREQUENCY, value.frequency)
            put(Words.APP_ID, 0) // Observed for comparison only, never an ownership token.
        }
        val uri = resolver.insert(Words.CONTENT_URI, values) ?: return null
        if (uri.scheme != "content" || uri.authority != Words.CONTENT_URI.authority ||
            uri.pathSegments.size != 2 || uri.pathSegments.first() != "words" ||
            uri.query != null || uri.fragment != null) throw ProbeStopped("Unexpected insert URI")
        return uri.lastPathSegment?.toLongOrNull() ?: throw ProbeStopped("Invalid insert ID")
    }

    override fun update(id: Long, expected: ProbeRow, frequency: Int): Int {
        val predicate = exactPredicate(expected)
        return resolver.update(ContentUris.withAppendedId(Words.CONTENT_URI, id),
            ContentValues().apply { put(Words.FREQUENCY, frequency) }, predicate.sql, predicate.args.toTypedArray())
    }

    override fun delete(id: Long, expected: ProbeRow): Int {
        val predicate = exactPredicate(expected)
        return resolver.delete(ContentUris.withAppendedId(Words.CONTENT_URI, id), predicate.sql, predicate.args.toTypedArray())
    }
}
