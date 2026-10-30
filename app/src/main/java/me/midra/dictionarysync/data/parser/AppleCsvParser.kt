package me.midra.dictionarysync.data.parser

import me.midra.dictionarysync.data.parser.TsvParser.fail
import me.midra.dictionarysync.data.parser.TsvParser.validateValue

/**
 * Apple's add-on dictionary CSV: reading,word,partOfSpeech or reading,word,partOfSpeech,,memo. A hand-written
 * reading,word,partOfSpeech,memo is read the same way. With [skipShort] (the format was chosen by hand), lines with fewer
 * than three columns are skipped and reported; the automatic candidate check stays strict.
 */
internal object AppleCsvParser {
    fun parse(text: String, encoding: String, skipShort: Boolean = true): ParsedDictionary {
        val lines = text.split(Regex("\r\n|\n|\r"))
        val entries = ArrayList<ParsedEntry>()
        val skipped = ArrayList<Int>()
        lines.forEachIndexed { index, line ->
            val number = index + 1
            if (line.toByteArray(Charsets.UTF_8).size > 64 * 1024) fail("RECORD_LIMIT", number)
            if (line.isBlank()) return@forEachIndexed
            val fields = ArrayList<String>()
            val value = StringBuilder()
            var state = 0 // 0=start, 1=unquoted, 2=quoted, 3=closing quote
            for (c in line) {
                when (state) {
                    0 -> when (c) { '"' -> state = 2; ',' -> fields.add(""); else -> { value.append(c); state = 1 } }
                    1 -> when (c) { '"' -> fail("CSV_QUOTE", number); ',' -> { fields.add(value.toString()); value.clear(); state = 0 }; else -> value.append(c) }
                    2 -> if (c == '"') state = 3 else value.append(c)
                    3 -> when (c) { '"' -> { value.append(c); state = 2 }; ',' -> { fields.add(value.toString()); value.clear(); state = 0 }; else -> fail("CSV_QUOTE", number) }
                }
                if (fields.size > 4) fail("CSV_COLUMNS", number)
            }
            if (state == 2) fail("CSV_QUOTE", number)
            fields.add(value.toString())
            if (skipShort && fields.size < 3) { skipped += number; return@forEachIndexed }
            // Apple introduces the memo with two commas, so its fourth field is an empty separator; with four fields it is the memo.
            if (fields.size !in 3..5) fail("CSV_COLUMNS", number)
            if (fields.size == 5 && fields[3].isNotEmpty()) fail("CSV_FOURTH_COLUMN", number)
            val memo = fields.size - 1
            fields.forEachIndexed { column, field -> validateValue(field, when { column == 2 -> 256; column == memo && column >= 3 -> 4096; else -> 1024 }, number) }
            if (fields[1].isBlank()) fail("EMPTY_WORD", number)
            entries += ParsedEntry(number, fields[0], fields[1], fields[2], if (fields.size > 3) fields[memo] else "")
            if (entries.size > TsvParser.MAX_ENTRIES) fail("ENTRY_LIMIT", number)
        }
        // Nothing but short lines means the wrong format, not a dictionary with a few slips.
        if (entries.isEmpty() && skipped.isNotEmpty()) fail("CSV_COLUMNS", skipped.first())
        return ParsedDictionary(entries, lines.size - if (lines.last().isEmpty()) 1 else 0, PARSER, encoding, skipped)
    }

    const val PARSER = "apple-csv-v1"
}
