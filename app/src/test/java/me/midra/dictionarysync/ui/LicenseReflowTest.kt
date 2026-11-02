package me.midra.dictionarysync.ui

import me.midra.dictionarysync.ui.settings.reflow
import org.junit.Assert.assertEquals
import org.junit.Test

class LicenseReflowTest {
    @Test fun joinsHardWrappedParagraphsAndDropsIndentationAndOuterBlankLines() {
        val text = "\n                    Apache License\n               Version 2.0\n\n   1. Definitions.\n\n      \"License\" shall mean\n      the terms.\n\n"
        assertEquals("Apache License\nVersion 2.0\n\n1. Definitions.\n\n\"License\" shall mean the terms.", reflow(text))
    }
}
