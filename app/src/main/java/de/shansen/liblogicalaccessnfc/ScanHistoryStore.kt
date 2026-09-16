package de.shansen.liblogicalaccessnfc

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ScanHistoryStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "scan-history.json"))
    fun load(): MutableList<ScanHistoryItem> {
        if (!file.baseFile.exists()) return mutableListOf()
        val rows = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        return MutableList(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            ScanHistoryItem(
                uid = row.getString("uid"), cardLabel = row.getString("label"),
                timestamp = row.getLong("time"),
                detectedPiccKeyLabel = row.optString("picc").takeIf { it.isNotEmpty() },
                savedCardText = row.getString("card"), savedEnvironmentText = row.optString("environment"),
                isExpanded = row.optBoolean("expanded")
            )
        }
    }
    fun save(items: List<ScanHistoryItem>) {
        val rows = JSONArray()
        items.forEach { item -> rows.put(JSONObject().apply {
            put("uid", item.uid); put("label", item.cardLabel); put("time", item.timestamp)
            put("picc", item.detectedPiccKeyLabel ?: "")
            put("card", item.cardText()); put("environment", item.environmentText())
            put("expanded", item.isExpanded)
        }) }
        val stream = file.startWrite()
        try { stream.write(rows.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
