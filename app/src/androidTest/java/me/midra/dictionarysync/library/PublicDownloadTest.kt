package me.midra.dictionarysync.library

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.midra.dictionarysync.data.parser.TsvParser
import me.midra.dictionarysync.data.source.AndroidDownloadRoute
import me.midra.dictionarysync.data.source.Downloader
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real HTTPS smoke test; URL is an instrumentation argument, never an embedded dictionary. */
@RunWith(AndroidJUnit4::class)
class PublicDownloadTest {
    @Test fun fetchSelectedPublicFileOverApprovedWifi() {
        val args = InstrumentationRegistry.getArguments()
        // Encode the entire URL so the test runner's device shell cannot interpret '&' as syntax.
        val url = args.getString("dictionaryUrlBase64")?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
        assumeNotNull(url)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AndroidDownloadRoute(context, true).use { Downloader().get(url!!, it) }
        val parsed = TsvParser.parse(result.bytes, contentType = result.contentType)
        args.getString("expectedEntries")?.toInt()?.let { assertEquals(it, parsed.entries.size) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "Public HTTPS: bytes=${result.bytes.size}; entries=${parsed.entries.size}; encoding=${parsed.encoding}; sha256=${result.sha256}\n")
        })
    }
}
