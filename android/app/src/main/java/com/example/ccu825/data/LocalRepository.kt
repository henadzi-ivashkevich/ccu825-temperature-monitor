package com.example.ccu825.data

import android.content.Context
import com.example.ccu825.Reading
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

class LocalRepository(private val context: Context) {

    private val fileName = "ccu_readings.json"

    private fun getStorageFile(): File {
        return File(context.filesDir, fileName)
    }

    @Synchronized
    fun loadReadings(): List<Reading> {
        val file = getStorageFile()
        if (!file.exists()) return emptyList()

        return try {
            val content = file.readText()
            if (content.isBlank()) return emptyList()
            parseReadingsJson(content)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    @Synchronized
    fun appendReadings(newReadings: List<Reading>): List<Reading> {
        if (newReadings.isEmpty()) return loadReadings()

        val existing = loadReadings()
        // Deduplicate by timestamp, prioritizing new data
        val readingsMap = LinkedHashMap<Long, Reading>()
        existing.forEach { readingsMap[it.time] = it }
        newReadings.forEach { readingsMap[it.time] = it }

        val combined = readingsMap.values.sortedBy { it.time }
        saveAllReadings(combined)
        return combined
    }

    @Synchronized
    fun saveAllReadings(readings: List<Reading>) {
        val array = JSONArray()
        readings.forEach { reading ->
            val obj = JSONObject().apply {
                put("time", reading.time)
                val inputsArray = JSONArray()
                reading.values.forEach { valItem ->
                    if (valItem != null && valItem.isFinite()) {
                        inputsArray.put(valItem)
                    } else {
                        inputsArray.put(JSONObject.NULL)
                    }
                }
                put("inputs", inputsArray)
            }
            array.put(obj)
        }
        val file = getStorageFile()
        file.writeText(array.toString(2))
    }

    fun exportToStream(outputStream: OutputStream): Boolean {
        return try {
            val file = getStorageFile()
            if (file.exists()) {
                file.inputStream().use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                true
            } else {
                // If file does not exist yet, write empty JSON array
                outputStream.use { output ->
                    output.write("[]".toByteArray())
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Synchronized
    fun importReadingsJson(jsonString: String): List<Reading> {
        val trimmed = jsonString.trim()
        val parsed = try {
            if (trimmed.startsWith("[")) {
                parseReadingsJson(trimmed)
            } else {
                val root = JSONObject(trimmed)
                if (root.has("readings")) {
                    parseReadingsJson(root.getJSONArray("readings").toString())
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        return if (parsed.isNotEmpty()) appendReadings(parsed) else emptyList()
    }

    private fun parseReadingsJson(jsonString: String): List<Reading> {
        val result = mutableListOf<Reading>()
        val array = JSONArray(jsonString)
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val time = row.optLong("time", 0L)
            if (time == 0L) continue

            val inputValues = row.optJSONArray("inputs")
            val values = if (inputValues != null) {
                (0 until 16).map { inputValues.optNullableDouble(it) }
            } else {
                MutableList<Double?>(16) { null }.apply {
                    set(3, row.optNullableDouble("outside"))
                    set(5, row.optNullableDouble("floor2"))
                    set(11, row.optNullableDouble("floor1"))
                }
            }
            result.add(Reading(time, values))
        }
        return result.sortedBy { it.time }
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (isNull(name) || !has(name)) return null
        val value = optDouble(name)
        return if (value.isFinite()) value else null
    }

    private fun JSONArray.optNullableDouble(index: Int): Double? {
        val value = opt(index)
        return when (value) {
            null, JSONObject.NULL -> null
            is Number -> value.toDouble().takeIf { it.isFinite() }
            is Boolean -> if (value) 1.0 else 0.0
            else -> {
                val text = value.toString().trim()
                when {
                    text.equals("true", ignoreCase = true) || text.equals("on", ignoreCase = true) -> 1.0
                    text.equals("false", ignoreCase = true) || text.equals("off", ignoreCase = true) -> 0.0
                    else -> text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
                }
            }
        }
    }
}
