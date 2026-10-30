package me.midra.dictionarysync.probe

data class ExactPredicate(val sql: String, val args: List<String>)

/** NULL and empty strings deliberately remain distinct. Values never enter SQL text. */
fun exactPredicate(row: ProbeRow): ExactPredicate {
    val args = mutableListOf<String>()
    val fields = listOf("_id" to row.id.toString(), "word" to row.word, "shortcut" to row.shortcut,
        "locale" to row.locale, "frequency" to row.frequency?.toString(), "appid" to row.appId?.toString())
    val sql = fields.joinToString(" AND ") { (column, value) ->
        if (value == null) "$column IS NULL" else {
            args += value
            "$column = ?"
        }
    }
    return ExactPredicate(sql, args)
}
