package com.example.ccu825.data

import com.example.ccu825.Reading
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Base64

class CcuApiClient {

    fun fetchReadings(
        endpoint: String,
        username: String = "",
        password: String = "",
        from: LocalDate? = null,
        to: LocalDate? = null
    ): List<Reading> {
        if (endpoint.isBlank()) error("Адрес сервера/контроллера не указан.")

        val fullUrlStr = buildUrl(endpoint, username, password, from, to)
        val url = URL(fullUrlStr)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000

            if (username.isNotBlank() || password.isNotBlank()) {
                val authStr = "$username:$password"
                val encodedAuth = Base64.getEncoder().encodeToString(authStr.toByteArray(Charsets.UTF_8))
                setRequestProperty("Authorization", "Basic $encodedAuth")
            }
        }

        try {
            val responseCode = connection.responseCode
            val stream: InputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }

            if (responseCode !in 200..299) {
                error("HTTP $responseCode: ${body.take(200)}")
            }

            return parseResponse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildUrl(
        endpoint: String,
        username: String,
        password: String,
        from: LocalDate?,
        to: LocalDate?
    ): String {
        val sb = StringBuilder(endpoint.trim())
        val hasQueryParams = sb.contains('?')
        var separator = if (hasQueryParams) '&' else '?'

        fun appendParam(key: String, value: String) {
            sb.append(separator).append(key).append('=').append(java.net.URLEncoder.encode(value, "UTF-8"))
            separator = '&'
        }

        if (from != null && to != null) {
            appendParam("action", "history")
            appendParam("from", from.toString())
            appendParam("to", to.toString())
        }

        if (username.isNotBlank()) appendParam("username", username)
        if (password.isNotBlank()) appendParam("password", password)

        return sb.toString()
    }

    private fun parseResponse(jsonString: String): List<Reading> {
        val trimmed = jsonString.trim()
        if (trimmed.startsWith("[")) {
            return parseReadingsArray(JSONArray(trimmed))
        }

        val root = JSONObject(trimmed)
        if (root.has("error") && !root.optBoolean("ok", true)) {
            error(root.optString("error", "Ошибка сервера CCU"))
        }

        if (root.has("readings")) {
            return parseReadingsArray(root.getJSONArray("readings"))
        }

        val single = parseSingleReading(root)
        return if (single != null) listOf(single) else emptyList()
    }

    private fun parseReadingsArray(rows: JSONArray): List<Reading> {
        val list = mutableListOf<Reading>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val reading = parseSingleReading(row)
            if (reading != null) list.add(reading)
        }
        return list.sortedBy { it.time }
    }

    private fun parseSingleReading(row: JSONObject): Reading? {
        val time = when {
            row.has("time") -> row.optLong("time")
            row.has("Time") -> row.optLong("Time")
            row.has("timestamp") -> row.optLong("timestamp")
            row.has("Timestamp") -> row.optLong("Timestamp")
            else -> System.currentTimeMillis()
        }
        if (time == 0L) return null

        val inputValuesArray = row.optJSONArray("inputs")
            ?: row.optJSONArray("Inputs")
            ?: row.optJSONArray("INPUTS")

        val values = if (inputValuesArray != null) {
            (0 until 16).map { i ->
                extractInputValue(inputValuesArray.opt(i), inputNo = i + 1)
            }
        } else {
            MutableList<Double?>(16) { null }.apply {
                (0 until 16).forEach { i ->
                    val inputNo = i + 1
                    val keys = listOf(
                        "in$inputNo", "In$inputNo", "IN$inputNo",
                        "input$inputNo", "Input$inputNo", "INPUT$inputNo"
                    )
                    var valFound: Double? = null
                    for (k in keys) {
                        if (row.has(k) && !row.isNull(k)) {
                            valFound = extractInputValue(row.opt(k), inputNo)
                            if (valFound != null) break
                        }
                    }
                    if (valFound == null) {
                        when (inputNo) {
                            1 -> valFound = extractInputValue(row.opt("volume"), 1)
                            2 -> valFound = extractInputValue(row.opt("smoke2"), 2)
                            4 -> valFound = extractInputValue(row.opt("outside"), 4)
                            5 -> valFound = extractInputValue(row.opt("leak"), 5)
                            6 -> valFound = extractInputValue(row.opt("floor2"), 6)
                            12 -> valFound = extractInputValue(row.opt("floor1"), 12)
                        }
                    }
                    set(i, valFound)
                }
            }
        }
        return Reading(time, values)
    }

    private fun extractInputValue(item: Any?, inputNo: Int): Double? {
        if (item == null || item == JSONObject.NULL) return null

        if (item is JSONObject) {
            // 1. Direct temperature / value properties
            listOf("temperature", "Temperature", "temp", "Temp", "value", "Value", "val", "Val").forEach { key ->
                if (item.has(key) && !item.isNull(key)) {
                    val v = item.optDouble(key)
                    if (v.isFinite()) return Math.round(v * 100.0) / 100.0
                }
            }

            // 2. ADC / Voltage conversion for temperature inputs (4, 6, 12)
            if (inputNo in setOf(4, 6, 12)) {
                listOf("Voltage", "voltage", "adc", "ADC", "mV", "mv").forEach { key ->
                    if (item.has(key) && !item.isNull(key)) {
                        val adc = item.optDouble(key)
                        if (adc.isFinite() && adc > 0) {
                            return convertAdcToTemperature(adc)
                        }
                    }
                }
            }

            // 3. Active / state property
            listOf("Active", "active", "state", "State", "status", "Status").forEach { key ->
                if (item.has(key) && !item.isNull(key)) {
                    val v = item.opt(key)
                    return when (v) {
                        is Number -> v.toDouble().takeIf { it.isFinite() }
                        is Boolean -> if (v) 1.0 else 0.0
                        else -> v?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }
                    }
                }
            }

            return null
        }

        // Primitive item (Number, Boolean, String)
        val num: Double? = when (item) {
            is Number -> item.toDouble().takeIf { it.isFinite() }
            is Boolean -> if (item) 1.0 else 0.0
            else -> {
                val text = item.toString().trim()
                when {
                    text.equals("true", ignoreCase = true) || text.equals("on", ignoreCase = true) -> 1.0
                    text.equals("false", ignoreCase = true) || text.equals("off", ignoreCase = true) -> 0.0
                    else -> text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
                }
            }
        }

        if (num == null) return null
        val dValue: Double = num

        // If input is temperature sensor (4, 6, 12) and value is raw ADC (> 100)
        if (inputNo in setOf(4, 6, 12) && dValue > 100.0) {
            return convertAdcToTemperature(dValue)
        }

        return Math.round(dValue * 100.0) / 100.0
    }

    private fun convertAdcToTemperature(adc: Double): Double {
        val voltage = adc * 10.0 / 4095.0
        val temp = (voltage / 5.0 - 0.5) / 0.01
        return Math.round(temp * 100.0) / 100.0
    }
}
