package com.example.ccu825

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val inputNames = listOf(
    "Объем", "Дымовой 2эт", "Люк УГБО", "Улица °C", "Протечка", "2 этаж °C",
    "Камин тепловой", "Уровень УГБО", "Гараж ворота", "Гараж вибро", "Гараж дым",
    "1 этаж °C", "Вход 13", "Вход 14", "Вход 15", "Вход 16"
)
private val inputColors = listOf(
    0xFF1976D2, 0xFFE65100, 0xFF388E3C, 0xFF8E24AA, 0xFF00838F, 0xFFC62828,
    0xFF6D4C41, 0xFF3949AB, 0xFF7CB342, 0xFFAD1457, 0xFF5E35B1, 0xFF039BE5,
    0xFFF4511E, 0xFF546E7A, 0xFF9E9D24, 0xFF00ACC1
).map { androidx.compose.ui.graphics.Color(it) }

data class TemperatureSeries(val input: Int, val title: String, val color: androidx.compose.ui.graphics.Color)
data class Reading(val time: Long, val values: List<Double?>) {
    fun value(input: Int): Double? = values.getOrNull(input - 1)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TemperatureScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemperatureScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ccu825", 0) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "") ?: "") }
    var showSettings by remember { mutableStateOf(endpoint.isBlank()) }
    var visibleInputs by remember {
        mutableStateOf(prefs.getStringSet("visibleInputs", null)?.mapNotNull(String::toIntOrNull)?.toSet() ?: setOf(4, 6, 12))
    }
    var names by remember {
        mutableStateOf(inputNames.mapIndexed { index, name -> (index + 1) to (prefs.getString("inputName_${index + 1}", name) ?: name) }.toMap())
    }
    var draftEndpoint by remember { mutableStateOf(endpoint) }
    var draftVisibleInputs by remember { mutableStateOf(visibleInputs) }
    var draftNames by remember { mutableStateOf(names) }
    var from by remember { mutableStateOf(LocalDate.now().minusDays(14)) }
    var to by remember { mutableStateOf(LocalDate.now()) }
    var selectedDays by remember { mutableIntStateOf(15) }
    var readings by remember { mutableStateOf<List<Reading>>(emptyList()) }
    var message by remember { mutableStateOf("Укажите URL веб-приложения Apps Script в настройках.") }
    var loading by remember { mutableStateOf(false) }
    var dateTarget by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (endpoint.isBlank()) { showSettings = true; return }
        loading = true
        message = "Загружаю данные…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { loadReadings(endpoint, from, to) } }
                .onSuccess { result -> readings = result; message = if (result.isEmpty()) "За этот период измерений нет." else "Измерений: ${result.size}" }
                .onFailure { message = "Не удалось загрузить данные: ${it.message ?: "проверьте адрес и подключение"}" }
            loading = false
        }
    }

    LaunchedEffect(endpoint, from, to) { if (endpoint.isNotBlank()) refresh() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Температура CCU825") }, actions = {
            TextButton(onClick = {
                draftEndpoint = endpoint
                draftVisibleInputs = visibleInputs
                draftNames = names
                showSettings = true
            }) { Text("Настройки") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { days ->
                        FilterChip(selected = selectedDays == days, onClick = {
                            selectedDays = days; from = LocalDate.now().minusDays((days - 1).toLong()); to = LocalDate.now()
                        }, label = { Text("$days дней") })
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Период", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { dateTarget = "from" }) { Text(from.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
                            Text("—")
                            OutlinedButton(onClick = { dateTarget = "to" }) { Text(to.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
                        }
                    }
                }
            }
            item { TemperatureChart(readings, chartSeries(visibleInputs, names)) }
            item { Text(message, style = MaterialTheme.typography.bodyMedium) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    chartSeries(visibleInputs, names).chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { item -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Canvas(Modifier.size(10.dp)) { drawCircle(item.color) }
                        Text("${item.input}. ${item.title}", style = MaterialTheme.typography.labelMedium)
                    } }
                    } }
                }
            }
            item { Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "Загрузка…" else "Обновить") } }
        }
    }

    if (showSettings) AlertDialog(
        onDismissRequest = { if (endpoint.isNotBlank()) showSettings = false },
        title = { Text("Настройки графика") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("URL развернутого веб-приложения Apps Script", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = draftEndpoint, onValueChange = { draftEndpoint = it }, singleLine = true, placeholder = { Text("https://script.google.com/macros/s/…/exec") })
                Text("Выберите входы для графика и при необходимости задайте свои названия.", style = MaterialTheme.typography.bodyMedium)
                inputNames.forEachIndexed { index, defaultName ->
                    val input = index + 1
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Checkbox(checked = input in draftVisibleInputs, onCheckedChange = { checked ->
                            draftVisibleInputs = if (checked) draftVisibleInputs + input else draftVisibleInputs - input
                        })
                        Text("$input", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(26.dp))
                        OutlinedTextField(
                            value = draftNames[input] ?: defaultName,
                            onValueChange = { value -> draftNames = draftNames + (input to value) },
                            singleLine = true,
                            label = { Text(defaultName) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            endpoint = draftEndpoint.trim()
            visibleInputs = draftVisibleInputs
            names = draftNames.mapValues { (input, label) -> label.trim().ifEmpty { inputNames[input - 1] } }
            prefs.edit().putString("endpoint", endpoint).apply()
            prefs.edit().putStringSet("visibleInputs", visibleInputs.map(Int::toString).toSet()).apply()
            names.forEach { (input, label) -> prefs.edit().putString("inputName_$input", label).apply() }
            showSettings = false
            refresh()
        }) { Text("Сохранить") } },
        dismissButton = { if (endpoint.isNotBlank()) TextButton(onClick = { showSettings = false }) { Text("Отмена") } }
    )

    dateTarget?.let { target ->
        val initial = if (target == "from") from else to
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { dateTarget = null }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { millis ->
                    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                    if (target == "from") from = date else to = date
                    selectedDays = 0
                }
                dateTarget = null
            }) { Text("Готово") }
        }, dismissButton = { TextButton(onClick = { dateTarget = null }) { Text("Отмена") } }) { DatePicker(state = picker) }
    }
}

@Composable
private fun TemperatureChart(readings: List<Reading>, series: List<TemperatureSeries>) {
    var selectedIndex by remember(readings) { mutableStateOf<Int?>(null) }
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("График показаний", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (readings.isEmpty() || series.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                    Text(if (series.isEmpty()) "В настройках выберите входы для графика" else "Нет данных для графика")
                }
            } else {
                Canvas(Modifier.fillMaxWidth().height((300 + (series.size - 4).coerceAtLeast(0) * 18).dp).pointerInput(readings) {
                    awaitEachGesture {
                        val graphLeft = 58.dp.toPx()
                        val graphRight = size.width - 72.dp.toPx()
                        fun selectAt(x: Float) {
                            if (readings.isEmpty()) return
                            val ratio = ((x - graphLeft) / (graphRight - graphLeft).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            val firstTime = readings.first().time
                            val timestamp = firstTime + (ratio.toDouble() * (readings.last().time - firstTime).toDouble()).toLong()
                            selectedIndex = readings.indices.minByOrNull { kotlin.math.abs(readings[it].time - timestamp) }
                        }
                        val down = awaitFirstDown(requireUnconsumed = false)
                        selectAt(down.position.x)
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            selectAt(change.position.x)
                            change.consume()
                        } while (event.changes.any { it.pressed })
                        selectedIndex = null
                    }
                }) {
                    val left = 58f * density; val right = size.width - 72f * density; val top = 26f * density; val bottom = size.height - 48f * density
                    val values = readings.flatMap { point -> series.mapNotNull { point.value(it.input) } }
                    if (values.isEmpty()) return@Canvas
                    val padding = ((values.maxOrNull()!! - values.minOrNull()!!).takeIf { it > 0 } ?: 1.0) * 0.08
                    val low = (values.minOrNull()!! - padding).toFloat(); val high = (values.maxOrNull()!! + padding).toFloat()
                    val span = (high - low).coerceAtLeast(0.01f)
                    val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.DKGRAY
                        textSize = 13f * density
                    }
                    val axisPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.GRAY
                        textSize = 12f * density
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val tempPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.DKGRAY
                        textSize = 12f * density
                    }
                    val unit = if (series.all { it.input in setOf(4, 6, 12) }) "°C" else "Значение"
                    drawContext.canvas.nativeCanvas.drawText(unit, 2f, 15f * density, tempPaint)
                    repeat(5) { i ->
                        val y = top + (bottom - top) * i / 4f
                        drawLine(androidx.compose.ui.graphics.Color.LightGray, Offset(left, y), Offset(right, y), 1.2f * density)
                        val digits = if (unit == "°C") 1 else 2
                        drawContext.canvas.nativeCanvas.drawText(String.format(Locale.getDefault(), "%.$digits" + "f", high - span * i / 4f), 2f, y + 5f * density, labelPaint)
                    }
                    val firstTime = readings.first().time
                    val timeRange = (readings.last().time - firstTime).coerceAtLeast(1L).toDouble()
                    val timeSpanDays = ChronoUnit.DAYS.between(
                        Instant.ofEpochMilli(readings.first().time).atZone(ZoneId.systemDefault()).toLocalDate(),
                        Instant.ofEpochMilli(readings.last().time).atZone(ZoneId.systemDefault()).toLocalDate()
                    )
                    val timeFormat = if (timeSpanDays < 2) DateTimeFormatter.ofPattern("HH:mm") else DateTimeFormatter.ofPattern("dd.MM")
                    val xLabelTimes = listOf(readings.first().time, readings[readings.size / 2].time, readings.last().time).distinct()
                    xLabelTimes.forEach { timestamp ->
                        val ratio = ((timestamp - firstTime).toDouble() / timeRange).toFloat()
                        val x = left + ratio * (right - left)
                        drawLine(androidx.compose.ui.graphics.Color.Gray, Offset(x, bottom), Offset(x, bottom + 5f * density), 1.2f * density)
                        val label = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(timeFormat)
                        drawContext.canvas.nativeCanvas.drawText(label, x, size.height - 5f * density, axisPaint)
                    }
                    series.forEach { item ->
                        val points = readings.mapNotNull { row -> row.value(item.input)?.let { value ->
                            val xRatio = ((row.time - firstTime).toDouble() / timeRange).toFloat()
                            Offset(left + xRatio * (right - left), bottom - ((value.toFloat() - low) / span) * (bottom - top))
                        } }
                        if (points.size > 1) {
                            val path = Path().apply { moveTo(points.first().x, points.first().y); points.drop(1).forEach { lineTo(it.x, it.y) } }
                            drawPath(path, item.color, style = Stroke(width = 3f))
                        }
                        points.forEach { drawCircle(item.color, radius = 3f, center = it) }
                    }

                    selectedIndex?.let { index ->
                        val row = readings.getOrNull(index)
                        if (row != null) {
                            val ratio = ((row.time - firstTime).toDouble() / timeRange).toFloat()
                            val x = left + ratio * (right - left)
                            drawLine(androidx.compose.ui.graphics.Color.DarkGray, Offset(x, top), Offset(x, bottom), 1.5f * density)
                            series.forEach { item -> row.value(item.input)?.let { value ->
                                val y = bottom - ((value.toFloat() - low) / span) * (bottom - top)
                                drawCircle(item.color, radius = 6f * density, center = Offset(x, y))
                                drawCircle(androidx.compose.ui.graphics.Color.White, radius = 2.5f * density, center = Offset(x, y))
                            } }
                        }
                    }

                    // Right-side labels show the latest available value for each sensor.
                    val latest = series.mapNotNull { item ->
                        readings.asReversed().firstNotNullOfOrNull { row ->
                            row.value(item.input)?.let { value ->
                                val y = bottom - ((value.toFloat() - low) / span) * (bottom - top)
                                Triple(item, value, y)
                            }
                        }
                    }.sortedBy { it.third }
                    val minLabelGap = 20f * density
                    val labelYs = latest.map { it.third }.toMutableList()
                    for (i in 1 until labelYs.size) {
                        if (labelYs[i] - labelYs[i - 1] < minLabelGap) labelYs[i] = labelYs[i - 1] + minLabelGap
                    }
                    if (labelYs.isNotEmpty() && labelYs.last() > bottom) {
                        val shift = labelYs.last() - bottom
                        for (i in labelYs.indices) labelYs[i] -= shift
                    }
                    if (labelYs.isNotEmpty() && labelYs.first() < top) {
                        val shift = top - labelYs.first()
                        for (i in labelYs.indices) labelYs[i] += shift
                    }
                    latest.forEachIndexed { i, (item, value, actualY) ->
                        val labelY = labelYs[i]
                        drawLine(item.color, Offset(right + 2f * density, actualY), Offset(right + 8f * density, labelY), 1.5f * density)
                        val valuePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(
                                (item.color.red * 255).toInt(), (item.color.green * 255).toInt(), (item.color.blue * 255).toInt()
                            )
                            textSize = 13f * density
                            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        }
                        val digits = if (item.input in setOf(4, 6, 12)) 1 else 2
                        val unitMark = if (item.input in setOf(4, 6, 12)) "°" else ""
                        drawContext.canvas.nativeCanvas.drawText(String.format(Locale.getDefault(), "%.$digits" + "f", value) + unitMark, right + 10f * density, labelY + 5f * density, valuePaint)
                    }
                }
                selectedIndex?.let { index -> readings.getOrNull(index)?.let { row ->
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Срез: ${Instant.ofEpochMilli(row.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        series.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Canvas(Modifier.size(9.dp)) { drawCircle(item.color) }
                                Text("${item.input}. ${item.title}: ${row.value(item.input)?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "—"}")
                            }
                        }
                    }
                } }
            }
        }
    }
}

private fun chartSeries(enabledInputs: Set<Int>, names: Map<Int, String>): List<TemperatureSeries> =
    enabledInputs.sorted().map { input -> TemperatureSeries(input, names[input].orEmpty().ifBlank { inputNames[input - 1] }, inputColors[input - 1]) }

private fun loadReadings(endpoint: String, from: LocalDate, to: LocalDate): List<Reading> {
    val separator = if (endpoint.contains('?')) '&' else '?'
    val url = URL("$endpoint${separator}action=history&from=$from&to=$to")
    val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 20_000; readTimeout = 30_000 }
    try {
        val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val root = JSONObject(body)
        if (!root.optBoolean("ok", false)) error(root.optString("error", "ошибка Apps Script"))
        val rows = root.getJSONArray("readings")
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val inputValues = row.optJSONArray("inputs")
            val values = if (inputValues != null) {
                (0 until 16).map { inputValues.optNullableDouble(it) }
            } else {
                // Compatibility with the earlier three-temperature response.
                MutableList<Double?>(16) { null }.apply {
                    set(3, row.optNullableDouble("outside"))
                    set(5, row.optNullableDouble("floor2"))
                    set(11, row.optNullableDouble("floor1"))
                }
            }
            Reading(row.getLong("time"), values)
        }.sortedBy { it.time }
    } finally { connection.disconnect() }
}

private fun JSONObject.optNullableDouble(name: String): Double? = if (isNull(name) || !has(name)) null else optDouble(name).takeIf { it.isFinite() }

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
