package me.midra.dictionarysync.data.source

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import me.midra.dictionarysync.data.parser.DictionaryProblem

interface DownloadRoute : AutoCloseable {
    fun check()
    fun open(uri: URI): HttpsURLConnection
}
data class HttpValidators(val finalUrl: String, val etag: String?, val lastModified: String?) {
    val usable get() = etag != null || lastModified != null
}
data class Download(val bytes: ByteArray, val sha256: String, val finalUrl: String, val contentType: String?,
    val etag: String? = null, val lastModified: String? = null, val notModified: Boolean = false)

/** Ignore malformed/unbounded validators rather than sending arbitrary response text as headers. */
object ValidatorHeaders {
    fun etag(value: String?): String? = value?.takeIf {
        val tag = it.removePrefix("W/")
        it.length <= 4096 && tag.length >= 2 && tag.first() == '"' && tag.last() == '"' &&
            tag.substring(1, tag.lastIndex).all { c -> c.code == 33 || c.code in 35..126 || c.code in 128..255 }
    }
    fun lastModified(value: String?): String? = value?.takeIf {
        it.length <= 128 && it.all { c -> c.code in 32..126 } &&
            runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME) }.isSuccess
    }
}

/**
 * Conditional headers are confined to the exact previously validated final URL on this route. [maxBytes] is read per
 * download, so a test can shrink it; by default it follows the heap ([MemoryBudget]).
 */
class Downloader(private val maxBytes: () -> Long = { MemoryBudget().maxBytes }, private val clock: () -> Long = System::nanoTime) {
    fun get(original: String, route: DownloadRoute, validators: HttpValidators? = null): Download {
        var uri = SourceRules.url(original)
        val visited = mutableSetOf<String>()
        val start = clock()
        fun check() {
            route.check()
            if (clock() - start > 120_000_000_000L) throw DictionaryProblem("TOTAL_TIMEOUT")
        }
        repeat(6) { hop ->
            check()
            if (!visited.add(uri.toString())) throw DictionaryProblem("REDIRECT_LOOP")
            val connection = route.open(uri)
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "DictionarySync/0.1 (Android; public dictionary fetch)")
                connection.setRequestProperty("Accept-Encoding", "gzip")
                val conditional = validators?.takeIf { it.finalUrl == uri.toString() }
                val etag = ValidatorHeaders.etag(conditional?.etag)
                val modified = ValidatorHeaders.lastModified(conditional?.lastModified)
                etag?.let { connection.setRequestProperty("If-None-Match", it) }
                modified?.let { connection.setRequestProperty("If-Modified-Since", it) }
                val status = connection.responseCode
                check()
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (hop == 5) throw DictionaryProblem("REDIRECT_LIMIT")
                    val location = connection.getHeaderField("Location") ?: throw DictionaryProblem("REDIRECT_LOCATION")
                    uri = SourceRules.url(uri.resolve(location).toString())
                } else {
                    if (status == 304) {
                        if (etag == null && modified == null) throw DictionaryProblem("UNEXPECTED_304")
                        val returnedTag = connection.getHeaderField("ETag")
                        if (etag != null && returnedTag != null && returnedTag.removePrefix("W/") != etag.removePrefix("W/"))
                            throw DictionaryProblem("INVALID_304")
                        return Download(byteArrayOf(), "", uri.toString(), null, notModified = true)
                    }
                    if (status != 200) throw DictionaryProblem("HTTP_$status", retryAfter = if (status == 429 || status == 503) connection.getHeaderField("Retry-After") else null)
                    if (connection.getHeaderField("Content-Range") != null) throw DictionaryProblem("PARTIAL_RESPONSE")
                    val length = connection.getHeaderFieldLong("Content-Length", -1)
                    val limit = maxBytes().coerceAtMost(Int.MAX_VALUE - 8L)
                    if (length > limit) throw DictionaryProblem("SIZE_LIMIT")
                    val encoding = connection.getHeaderField("Content-Encoding")?.trim()?.lowercase().orEmpty()
                    if (encoding !in setOf("", "identity", "gzip")) throw DictionaryProblem("CONTENT_ENCODING")
                    val raw = LimitedStream(connection.inputStream, limit, ::check)
                    val stream = if (encoding == "gzip") GZIPInputStream(raw) else raw
                    val bytes = if (length >= 0 && encoding != "gzip") stream.use { exact(it, length.toInt(), ::check) } else stream.use {
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            check()
                            val count = it.read(buffer)
                            if (count < 0) break
                            if (out.size() + count > limit) throw DictionaryProblem("SIZE_LIMIT")
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    check()
                    if (length >= 0 && raw.count != length) throw DictionaryProblem("INCOMPLETE_RESPONSE")
                    if (bytes.isEmpty()) throw DictionaryProblem("EMPTY_BODY")
                    return Download(bytes, sha256(bytes), uri.toString(), connection.contentType,
                        ValidatorHeaders.etag(connection.getHeaderField("ETag")), ValidatorHeaders.lastModified(connection.getHeaderField("Last-Modified")))
                }
            } catch (error: Exception) {
                route.check() // Route loss is a condition wait, even when disconnect surfaced as IOException.
                throw error
            } finally { connection.disconnect() }
        }
        throw DictionaryProblem("REDIRECT_LIMIT")
    }

    /**
     * Reads a body of a declared [length] into one array of that size, instead of a growing buffer and its final copy
     * (about three times the body at the peak). A longer or shorter body is caught by the INCOMPLETE_RESPONSE check.
     */
    private fun exact(input: InputStream, length: Int, check: () -> Unit): ByteArray {
        val bytes = ByteArray(length)
        var filled = 0
        while (filled < length) {
            check()
            val count = input.read(bytes, filled, length - filled)
            if (count < 0) return bytes.copyOf(filled)
            filled += count
        }
        input.read() // A byte past the declared length is counted, so the response is rejected as incomplete.
        return bytes
    }

    private class LimitedStream(input: InputStream, private val limit: Long, private val check: () -> Unit) : FilterInputStream(input) {
        var count = 0L
        private fun consumed(n: Int): Int { if (n > 0) count += n; if (count > limit) throw DictionaryProblem("SIZE_LIMIT"); check(); return n }
        override fun read(): Int { val value = `in`.read(); consumed(if (value < 0) 0 else 1); return value }
        override fun read(b: ByteArray, off: Int, len: Int) = consumed(`in`.read(b, off, len))
    }
    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
