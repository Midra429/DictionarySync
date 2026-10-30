package me.midra.dictionarysync.data.source

import java.io.IOException
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import javax.net.ssl.SSLException
import me.midra.dictionarysync.data.parser.DictionaryProblem

/** Business retry budget belongs to a persisted request generation, independently of Worker retries. */
object HttpRetry {
    const val MAX_RETRIES = 5
    fun retryable(error: Exception): Boolean {
        if (error is SSLException || error is java.util.zip.ZipException) return false
        if (error is IOException) return true
        val code = (error as? DictionaryProblem)?.code ?: return false
        val status = code.removePrefix("HTTP_").toIntOrNull()
        return status == 408 || status == 429 || status in 500..599 || code in setOf("TOTAL_TIMEOUT", "INCOMPLETE_RESPONSE")
    }
    fun backoff(failures: Int): Long = 30_000L shl (failures - 1).coerceIn(0, MAX_RETRIES - 1)
    fun add(now: Long, delay: Long): Long = if (delay > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delay

    /** RFC 9110 seconds + all three HTTP-date forms. Overflow means a long hold, never immediate retry. */
    fun retryAfter(value: String?, now: Long): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
        if (raw.all { it in '0'..'9' }) {
            val seconds = raw.toLongOrNull() ?: return Long.MAX_VALUE
            val delay = if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
            return add(now, delay)
        }
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val imf = Regex("([A-Za-z]{3}), ([0-9]{2}) ([A-Za-z]{3}) ([0-9]{4}) ([0-9]{2}):([0-9]{2}):([0-9]{2}) GMT").matchEntire(raw)
        val old = Regex("([A-Za-z]+), ([0-9]{2})-([A-Za-z]{3})-([0-9]{2}) ([0-9]{2}):([0-9]{2}):([0-9]{2}) GMT").matchEntire(raw)
        val ascii = Regex("([A-Za-z]{3}) ([A-Za-z]{3}) {1,2}([0-9]{1,2}) ([0-9]{2}):([0-9]{2}):([0-9]{2}) ([0-9]{4})").matchEntire(raw)
        val fields = when {
            imf != null -> imf.groupValues.drop(1)
            old != null -> old.groupValues.drop(1)
            ascii != null -> ascii.groupValues.let { listOf(it[1], it[3], it[2], it[7], it[4], it[5], it[6]) }
            else -> return null
        }
        return runCatching {
            val utc = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC)
            var year = fields[3].toInt()
            if (old != null) {
                val base = utc.year - 49
                year = base + Math.floorMod(year - Math.floorMod(base, 100), 100)
            }
            var date = LocalDateTime.of(year, months.indexOf(fields[2]) + 1, fields[1].toInt(), fields[4].toInt(), fields[5].toInt(), fields[6].toInt())
            if (old != null && date > utc.plusYears(50)) date = date.minusYears(100)
            val weekday = date.dayOfWeek.getDisplayName(if (old != null) TextStyle.FULL else TextStyle.SHORT, Locale.US)
            if (fields[0] != weekday) return null
            maxOf(now, date.toInstant(ZoneOffset.UTC).toEpochMilli())
        }.getOrNull()
    }
}
