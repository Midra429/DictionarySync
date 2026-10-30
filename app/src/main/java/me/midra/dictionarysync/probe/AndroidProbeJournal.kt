package me.midra.dictionarysync.probe

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class AndroidProbeJournal(context: Context) : ProbeJournal {
    private val directory = File(context.noBackupFilesDir, "probe").apply { mkdirs() }
    override fun load() = read("active-trial.json")
    override fun lastBasic() = read("last-basic.json")

    override fun save(session: ProbeSession) {
        val text = encode(session).toString(2)
        write("active-trial.json", text)
        if (session.finished) write("last-${session.kind.lowercase()}.json", text)
    }

    fun report(): String = load()?.let { session ->
        buildString {
            appendLine("Trial: ${session.kind}; finished=${session.finished}; basicPassed=${session.basicPassed}")
            appendLine("Unmanaged baseline rows: ${session.baselineCount}; digest stored on device")
            appendLine("Unsafe predicate: ${session.unsafePredicate}")
            session.records.forEach { record ->
                appendLine("${record.value.word} / ${record.value.shortcut ?: "NULL"} / ${record.value.locale ?: "NULL"}: id=${record.id}, verified=${record.confirmed != null}, removed=${record.removed}")
            }
            session.events.forEach { appendLine(it) }
        }
    } ?: ""

    private fun read(name: String): ProbeSession? {
        val file = AtomicFile(File(directory, name))
        if (!file.baseFile.exists() && !File(directory, "$name.bak").exists()) return null
        return decode(JSONObject(file.openRead().bufferedReader().use { it.readText() }))
    }

    private fun write(name: String, text: String) {
        val file = AtomicFile(File(directory, name))
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun value(value: ProbeValue) = JSONObject().apply {
        put("word", value.word); put("shortcut", value.shortcut ?: JSONObject.NULL)
        put("locale", value.locale ?: JSONObject.NULL); put("frequency", value.frequency)
    }
    private fun row(row: ProbeRow) = JSONObject().apply {
        put("id", row.id); put("word", row.word); put("shortcut", row.shortcut ?: JSONObject.NULL)
        put("locale", row.locale ?: JSONObject.NULL); put("frequency", row.frequency ?: JSONObject.NULL)
        put("appid", row.appId ?: JSONObject.NULL)
    }
    private fun encode(s: ProbeSession) = JSONObject().apply {
        put("schema", 1); put("kind", s.kind); put("phase", s.phase); put("marker", s.marker)
        put("witness", row(s.witness)); put("baselineHash", s.baselineHash); put("baselineCount", s.baselineCount)
        put("unsafePredicate", s.unsafePredicate); put("basicPassed", s.basicPassed); put("finished", s.finished)
        put("records", JSONArray().apply { s.records.forEach { r -> put(JSONObject().apply {
            put("value", value(r.value)); put("id", r.id ?: JSONObject.NULL); put("attempted", r.attempted)
            put("confirmed", r.confirmed?.let(::row) ?: JSONObject.NULL)
            put("updateTarget", r.updateTarget?.let(::row) ?: JSONObject.NULL); put("removed", r.removed)
        }) } })
        put("events", JSONArray(s.events))
    }
    private fun JSONObject.nullableString(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.nullableInt(key: String) = if (isNull(key)) null else getInt(key)
    private fun decodeRow(j: JSONObject) = ProbeRow(j.getLong("id"), j.getString("word"),
        j.nullableString("shortcut"), j.nullableString("locale"), j.nullableInt("frequency"), j.nullableInt("appid"))
    private fun decode(j: JSONObject): ProbeSession {
        require(j.getInt("schema") == 1)
        val records = j.getJSONArray("records")
        val events = j.getJSONArray("events")
        return ProbeSession(j.getString("kind"), j.getString("phase"), j.getString("marker"),
            decodeRow(j.getJSONObject("witness")), j.getString("baselineHash"), j.getInt("baselineCount"),
            List(records.length()) { i ->
                val r = records.getJSONObject(i)
                val v = r.getJSONObject("value")
                ProbeRecord(ProbeValue(v.getString("word"), v.nullableString("shortcut"), v.nullableString("locale"), v.getInt("frequency")),
                    if (r.isNull("id")) null else r.getLong("id"), r.getBoolean("attempted"),
                    if (r.isNull("confirmed")) null else decodeRow(r.getJSONObject("confirmed")),
                    if (r.isNull("updateTarget")) null else decodeRow(r.getJSONObject("updateTarget")), r.getBoolean("removed"))
            }, List(events.length()) { events.getString(it) }, j.getBoolean("unsafePredicate"),
            j.getBoolean("basicPassed"), j.getBoolean("finished"))
    }
}
