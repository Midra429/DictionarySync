package me.midra.dictionarysync.library

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.data.source.*
import org.junit.Assert.*
import org.junit.Test

class DownloaderTest {
    private class Response(val status: Int = 200, val body: ByteArray = "r\tw\tp".toByteArray(), val headers: Map<String, String> = emptyMap()) : HttpsURLConnection(URL("https://example.org")) {
        var disconnected = false
        var streamFailure: (() -> Nothing)? = null
        override fun getResponseCode() = status
        override fun getInputStream() = streamFailure?.invoke() ?: ByteArrayInputStream(body)
        override fun getHeaderField(name: String?) = headers[name]
        override fun getContentType() = headers["Content-Type"]
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private class Route(val responses: List<Response>) : DownloadRoute {
        val urls = mutableListOf<String>()
        var invalid = false
        override fun check() { if (invalid) throw DictionaryProblem("WIFI_WAIT") }
        override fun open(uri: URI): HttpsURLConnection { urls += uri.toString(); return responses[urls.lastIndex] }
        override fun close() {}
    }
    @Test fun redirectsKeepOriginalQueryAndUseLocationQueryWithoutCopying() {
        val first = Response(302, headers = mapOf("Location" to "/raw?z=%2f&z=2"))
        val route = Route(listOf(first, Response()))
        val result = Downloader().get("https://example.org/view?a=1&a=2", route)
        assertEquals(listOf("https://example.org/view?a=1&a=2", "https://example.org/raw?z=%2f&z=2"), route.urls)
        assertEquals(route.urls.last(), result.finalUrl); assertTrue(first.disconnected)
    }
    @Test fun downgradeLoopsAndSixthRedirectAreRejected() {
        listOf("http://example.org/d", "https://name:secret@example.org/d", "/d").forEach { target ->
            assertThrows(DictionaryProblem::class.java) { Downloader().get("https://example.org/d", Route(listOf(Response(302, headers = mapOf("Location" to target))))) }
        }
        val route = Route((1..6).map { Response(302, headers = mapOf("Location" to "/$it")) })
        assertEquals("REDIRECT_LIMIT", assertThrows(DictionaryProblem::class.java) { Downloader().get("https://example.org/d", route) }.code)
    }
    @Test fun unexpectedStatusEmptyAndTruncationAreFailures() {
        listOf(Response(204), Response(206), Response(304), Response(404), Response(500), Response(body = byteArrayOf()), Response(headers = mapOf("Content-Length" to "100"))).forEach { response ->
            assertThrows(DictionaryProblem::class.java) { Downloader().get("https://example.org/d", Route(listOf(response))) }
            assertTrue(response.disconnected)
        }
    }
    @Test fun declaredLengthIsReadExactlyAndAShorterOrLongerBodyFails() {
        val body = "r\tw\tp".toByteArray()
        fun get(length: Int) = Downloader().get("https://example.org/d", Route(listOf(Response(body = body, headers = mapOf("Content-Length" to "$length")))))
        assertArrayEquals(body, get(body.size).bytes)
        assertEquals("INCOMPLETE_RESPONSE", assertThrows(DictionaryProblem::class.java) { get(body.size + 1) }.code)
        assertEquals("INCOMPLETE_RESPONSE", assertThrows(DictionaryProblem::class.java) { get(body.size - 1) }.code)
    }
    @Test fun sizeLimitFollowsTheBudgetForDeclaredUndeclaredAndDecompressedBodies() {
        val body = "r\tw\tp\n".repeat(100).toByteArray()
        fun get(limit: Long, headers: Map<String, String> = emptyMap(), bytes: ByteArray = body) =
            Downloader(maxBytes = { limit }).get("https://example.org/d", Route(listOf(Response(body = bytes, headers = headers))))
        val declared = mapOf("Content-Length" to "${body.size}")
        assertEquals(body.size, get(body.size.toLong(), declared).bytes.size)
        assertEquals(body.size, get(body.size.toLong()).bytes.size)
        for (headers in listOf(declared, emptyMap())) {
            assertEquals("SIZE_LIMIT", assertThrows(DictionaryProblem::class.java) { get(body.size - 1L, headers) }.code)
        }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(body) }
        val compressed = output.toByteArray()
        assertTrue(compressed.size < body.size - 1)
        assertEquals("SIZE_LIMIT", assertThrows(DictionaryProblem::class.java) {
            get(body.size - 1L, mapOf("Content-Encoding" to "gzip", "Content-Length" to "${compressed.size}"), compressed)
        }.code)
    }
    @Test fun validGzipDecodesAndCorruptGzipFails() {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write("r\tw\tp".toByteArray()) }
        val compressed = output.toByteArray()
        val response = Response(body = compressed, headers = mapOf("Content-Encoding" to "gzip", "Content-Length" to compressed.size.toString()))
        assertEquals("r\tw\tp", Downloader().get("https://example.org/d", Route(listOf(response))).bytes.toString(Charsets.UTF_8))
        compressed[compressed.lastIndex] = 12
        assertThrows(java.io.IOException::class.java) { Downloader().get("https://example.org/d", Route(listOf(Response(body = compressed, headers = mapOf("Content-Encoding" to "gzip"))))) }
    }
    @Test fun unavailableRouteStartsNoConnectionAndDeadlineStops() {
        val route = Route(listOf(Response())).apply { invalid = true }
        assertThrows(DictionaryProblem::class.java) { Downloader().get("https://example.org/d", route) }
        assertTrue(route.urls.isEmpty())
        var clock = 0L
        assertEquals("TOTAL_TIMEOUT", assertThrows(DictionaryProblem::class.java) { Downloader { clock.also { clock += 121_000_000_000L } }.get("https://example.org/d", Route(listOf(Response()))) }.code)
    }

    @Test fun conditionalHeadersOnlyReachExactValidatedTargetAnd304DoesNotReadBody() {
        val first = Response(302, headers = mapOf("Location" to "https://other.example/raw?x=%2f"))
        val last = Response(304, body = byteArrayOf(), headers = mapOf("ETag" to "W/\"valid\""))
        val target = "https://other.example/raw?x=%2f"
        val result = Downloader().get("https://example.org/d", Route(listOf(first, last)), HttpValidators(target, "\"valid\"", "Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(first.getRequestProperty("If-None-Match"))
        assertEquals("\"valid\"", last.getRequestProperty("If-None-Match"))
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", last.getRequestProperty("If-Modified-Since"))
        assertTrue(result.notModified); assertTrue(result.bytes.isEmpty()); assertTrue(last.disconnected)
    }
    @Test fun changedRedirectDestinationGetsNoValidatorsAndReturns200() {
        val response = Response(headers = mapOf("ETag" to "\"new\""))
        val result = Downloader().get("https://example.org/d", Route(listOf(response)), HttpValidators("https://example.org/old", "\"old\"", null))
        assertNull(response.getRequestProperty("If-None-Match")); assertFalse(result.notModified)
        assertEquals("\"new\"", result.etag)
    }
    @Test fun invalidOrInjectedValidatorsAreNeverSentAndMismatched304Fails() {
        for (value in listOf("bare", "\"bad\r\nHeader: x\"", "\"" + "x".repeat(4096) + "\"")) {
            val response = Response()
            Downloader().get("https://example.org/d", Route(listOf(response)), HttpValidators("https://example.org/d", value, "bad"))
            assertNull(response.getRequestProperty("If-None-Match")); assertNull(response.getRequestProperty("If-Modified-Since"))
        }
        val error = assertThrows(DictionaryProblem::class.java) {
            Downloader().get("https://example.org/d", Route(listOf(Response(304, headers = mapOf("ETag" to "\"other\"")))), HttpValidators("https://example.org/d", "\"old\"", null))
        }
        assertEquals("INVALID_304", error.code)
    }

    @Test fun retryAfterIsCapturedOnlyOn429And503AndRouteLossWinsOverIoFailure() {
        for (status in listOf(429, 503, 500, 404)) {
            val e = assertThrows(DictionaryProblem::class.java) {
                Downloader().get("https://example.org/d", Route(listOf(Response(status, headers = mapOf("Retry-After" to "120")))))
            }
            assertEquals(if (status in setOf(429, 503)) "120" else null, e.retryAfter)
        }
        val response = Response(); val route = Route(listOf(response))
        response.streamFailure = { route.invalid = true; throw java.io.IOException("route disconnected") }
        val e = assertThrows(DictionaryProblem::class.java) { Downloader().get("https://example.org/d", route) }
        assertEquals("WIFI_WAIT", e.code); assertTrue(response.disconnected)
    }
}
