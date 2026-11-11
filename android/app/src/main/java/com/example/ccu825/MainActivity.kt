package com.example.ccu825

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ccu825.data.CcuApiClient
import com.example.ccu825.data.LocalRepository
import com.example.ccu825.worker.CcuFetchWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val inputNamesRu = listOf(
    "Объем", "Дымовой 2эт", "Люк УГБО", "Улица °C", "Протечка", "2 этаж °C",
    "Камин тепловой", "Уровень УГБО", "Гараж ворота", "Гараж вибро", "Гараж дым",
    "1 этаж °C", "Вход 13", "Вход 14", "Вход 15", "Вход 16"
)

private val inputNamesEn = listOf(
    "Volume", "Smoke 2nd fl", "Hatch UGBO", "Outdoor °C", "Leakage", "2nd floor °C",
    "Fireplace heat", "Level UGBO", "Garage gate", "Garage vibro", "Garage smoke",
    "1st floor °C", "Input 13", "Input 14", "Input 15", "Input 16"
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
    val repository = remember { LocalRepository(context) }
    val apiClient = remember { CcuApiClient() }
    val prefs = remember { context.getSharedPreferences("ccu825", 0) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var language by remember { mutableStateOf(prefs.getString("language", "ru") ?: "ru") }
    fun tr(ru: String, en: String): String = if (language == "en") en else ru

    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "") ?: "") }
    var username by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
    var password by remember { mutableStateOf(prefs.getString("password", "") ?: "") }
    var pollingInterval by remember { mutableIntStateOf(prefs.getInt("pollingInterval", 0)) }

    var showSettings by remember { mutableStateOf(endpoint.isBlank()) }
    var visibleInputs by remember {
        mutableStateOf(prefs.getStringSet("visibleInputs", null)?.mapNotNull(String::toIntOrNull)?.toSet() ?: setOf(4, 6, 12))
    }

    val defaultInputNames = if (language == "en") inputNamesEn else inputNamesRu
    var names by remember(language) {
        mutableStateOf(defaultInputNames.mapIndexed { index, defaultName ->
            (index + 1) to (prefs.getString("inputName_${index + 1}", defaultName) ?: defaultName)
        }.toMap())
    }

    var draftLanguage by remember { mutableStateOf(language) }
    var draftEndpoint by remember { mutableStateOf(endpoint) }
    var draftUsername by remember { mutableStateOf(username) }
    var draftPassword by remember { mutableStateOf(password) }
    var draftInterval by remember { mutableIntStateOf(pollingInterval) }
    var draftVisibleInputs by remember { mutableStateOf(visibleInputs) }
    var draftNames by remember { mutableStateOf(names) }

    fun trDraft(ru: String, en: String): String = if (draftLanguage == "en") en else ru

    var from by remember { mutableStateOf(LocalDate.now().minusDays(14)) }
    var to by remember { mutableStateOf(LocalDate.now()) }
    var selectedDays by remember { mutableIntStateOf(15) }
    var readings by remember { mutableStateOf<List<Reading>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var dateTarget by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadFromLocal() {
        scope.launch(Dispatchers.IO) {
            val allReadings = repository.loadReadings()
            val fromMillis = from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val toMillis = to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val filtered = allReadings.filter { it.time in fromMillis..toMillis }
            withContext(Dispatchers.Main) {
                readings = filtered
                message = if (filtered.isEmpty()) {
                    if (allReadings.isEmpty()) tr("Локальных измерений нет. Нажмите «Запросить с сервера» или импортируйте файл.", "No local measurements. Tap 'Fetch from server' or import a file.")
                    else tr("За выбранный период локальных измерений нет.", "No local measurements for selected period.")
                } else {
                    "${tr("Измерений", "Readings")}: ${filtered.size}"
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val success = context.contentResolver.openOutputStream(uri)?.use { stream ->
                    repository.exportToStream(stream)
                } ?: false
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (success) tr("Бэкап успешно сохранен!", "Backup saved successfully!")
                        else tr("Не удалось сохранить бэкап", "Failed to save backup"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val jsonText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (!jsonText.isNullOrBlank()) {
                        val imported = repository.importReadingsJson(jsonText)
                        withContext(Dispatchers.Main) {
                            loadFromLocal()
                            Toast.makeText(
                                context,
                                if (imported.isNotEmpty()) "${tr("Импортировано записей", "Imported records")}: ${imported.size}"
                                else tr("Не удалось распознать измерения в файле", "Failed to recognize measurements in file"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "${tr("Ошибка импорта", "Import error")}: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun refresh() {
        if (endpoint.isBlank()) { showSettings = true; return }
        loading = true
        message = tr("Запрашиваю данные у контроллера…", "Fetching data from controller…")
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val fetched = apiClient.fetchReadings(endpoint, username, password, from, to)
                    if (fetched.isNotEmpty()) {
                        repository.appendReadings(fetched)
                    }
                    fetched
                }
            }.onSuccess { fetched ->
                loadFromLocal()
                if (fetched.isEmpty()) {
                    message = tr("Сервер не вернул новых измерений.", "Server returned no new measurements.")
                }
            }.onFailure { err ->
                message = "${tr("Ошибка сети", "Network error")}: ${err.message ?: tr("проверьте адрес и учетные данные", "check address and credentials")}"
                loadFromLocal()
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadFromLocal()
    }

    LaunchedEffect(from, to) {
        loadFromLocal()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Температура CCU825", "CCU825 Temperature")) },
            actions = {
                TextButton(onClick = {
                    draftLanguage = language
                    draftEndpoint = endpoint
                    draftUsername = username
                    draftPassword = password
                    draftInterval = pollingInterval
                    draftVisibleInputs = visibleInputs
                    draftNames = names
                    showSettings = true
                }) { Text(tr("Настройки", "Settings")) }
            }
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 15, 30).forEach { days ->
                        FilterChip(
                            selected = selectedDays == days,
                            onClick = {
                                selectedDays = days
                                from = LocalDate.now().minusDays((days - 1).toLong())
                                to = LocalDate.now()
                            },
                            label = { Text("$days ${tr("дней", "days")}") }
                        )
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(tr("Период", "Period"), fontWeight = FontWeight.SemiBold)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { dateTarget = "from" },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(from.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("—", fontWeight = FontWeight.SemiBold)
                            OutlinedButton(
                                onClick = { dateTarget = "to" },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(to.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item { TemperatureChart(readings, chartSeries(visibleInputs, names, defaultInputNames), language) }
            item { Text(message, style = MaterialTheme.typography.bodyMedium) }
            item {
                val legendColumns = if (isLandscape) 4 else 2
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    chartSeries(visibleInputs, names, defaultInputNames).chunked(legendColumns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Canvas(Modifier.size(10.dp)) { drawCircle(item.color) }
                                    Text("${item.input}. ${item.title}", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { refresh() },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loading) tr("Загрузка…", "Loading…") else tr("Запросить с сервера", "Fetch from server"))
                }
            }
        }
    }

    if (showSettings) AlertDialog(
        onDismissRequest = { if (endpoint.isNotBlank()) showSettings = false },
        title = { Text(tr("Настройки подключения и графика", "Connection & Chart Settings")) },
        text = {
            val maxDialogHeight = if (isLandscape) 280.dp else 560.dp
            Column(
                Modifier.heightIn(max = maxDialogHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(trDraft("Язык интерфейса", "Interface Language"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draftLanguage == "ru",
                        onClick = {
                            draftLanguage = "ru"
                            draftNames = inputNamesRu.mapIndexed { i, dName -> (i + 1) to (prefs.getString("inputName_${i + 1}", dName) ?: dName) }.toMap()
                        },
                        label = { Text("Русский") }
                    )
                    FilterChip(
                        selected = draftLanguage == "en",
                        onClick = {
                            draftLanguage = "en"
                            draftNames = inputNamesEn.mapIndexed { i, dName -> (i + 1) to (prefs.getString("inputName_${i + 1}", dName) ?: dName) }.toMap()
                        },
                        label = { Text("English") }
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                Text(trDraft("Подключение к контроллеру / ccu.sh", "Controller / ccu.sh Connection"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = draftEndpoint,
                    onValueChange = { draftEndpoint = it },
                    singleLine = true,
                    label = { Text(trDraft("URL контроллера / ccu.sh", "Controller / ccu.sh URL")) },
                    placeholder = { Text("https://ccu.sh/api/…") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draftUsername,
                        onValueChange = { draftUsername = it },
                        singleLine = true,
                        label = { Text(trDraft("Логин", "Username")) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draftPassword,
                        onValueChange = { draftPassword = it },
                        singleLine = true,
                        label = { Text(trDraft("Пароль", "Password")) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                Text(trDraft("Фоновый автоопрос (WorkManager)", "Background Polling (WorkManager)"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(trDraft("Интервал обновления локальных данных в фоне:", "Background local update interval:"), style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        0 to trDraft("Выкл", "Off"),
                        15 to trDraft("15 минут", "15 minutes"),
                        30 to trDraft("30 минут", "30 minutes"),
                        60 to trDraft("60 минут", "60 minutes")
                    ).forEach { (valMin, label) ->
                        FilterChip(
                            selected = draftInterval == valMin,
                            onClick = { draftInterval = valMin },
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                Text(trDraft("Резервное копирование и импорт", "Backup & Import"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(trDraft("Импорт файла", "Import File"))
                    }
                    OutlinedButton(
                        onClick = {
                            val defaultName = "ccu_backup_${LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd"))}.json"
                            exportLauncher.launch(defaultName)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(trDraft("Экспорт бэкапа", "Export Backup"))
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                Text(trDraft("Отображаемые входы и названия", "Visible Inputs & Names"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val draftDefaultInputNames = if (draftLanguage == "en") inputNamesEn else inputNamesRu
                draftDefaultInputNames.forEachIndexed { index, defaultName ->
                    val input = index + 1
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Checkbox(
                            checked = input in draftVisibleInputs,
                            onCheckedChange = { checked ->
                                draftVisibleInputs = if (checked) draftVisibleInputs + input else draftVisibleInputs - input
                            }
                        )
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
        confirmButton = {
            TextButton(onClick = {
                language = draftLanguage
                endpoint = draftEndpoint.trim()
                username = draftUsername.trim()
                password = draftPassword.trim()
                pollingInterval = draftInterval
                visibleInputs = draftVisibleInputs
                names = draftNames.mapValues { (input, label) ->
                    val defNames = if (language == "en") inputNamesEn else inputNamesRu
                    label.trim().ifEmpty { defNames[input - 1] }
                }

                prefs.edit()
                    .putString("language", language)
                    .putString("endpoint", endpoint)
                    .putString("username", username)
                    .putString("password", password)
                    .putInt("pollingInterval", pollingInterval)
                    .putStringSet("visibleInputs", visibleInputs.map(Int::toString).toSet())
                    .apply()

                names.forEach { (input, label) -> prefs.edit().putString("inputName_$input", label).apply() }

                CcuFetchWorker.scheduleOrCancel(context, pollingInterval)

                showSettings = false
                refresh()
            }) { Text(trDraft("Сохранить", "Save")) }
        },
        dismissButton = {
            if (endpoint.isNotBlank()) {
                TextButton(onClick = { showSettings = false }) { Text(trDraft("Отмена", "Cancel")) }
            }
        }
    )

    dateTarget?.let { target ->
        val initial = if (target == "from") from else to
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { dateTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                        if (target == "from") from = date else to = date
                        selectedDays = 0
                    }
                    dateTarget = null
                }) { Text(tr("Готово", "Done")) }
            },
            dismissButton = { TextButton(onClick = { dateTarget = null }) { Text(tr("Отмена", "Cancel")) } }
        ) { DatePicker(state = picker) }
    }
}

@Composable
private fun TemperatureChart(readings: List<Reading>, series: List<TemperatureSeries>, language: String) {
    var selectedIndex by remember(readings) { mutableStateOf<Int?>(null) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val baseHeight = if (isLandscape) 220 else 300
    val extraHeight = (series.size - 4).coerceAtLeast(0) * (if (isLandscape) 10 else 18)
    val chartHeight = (baseHeight + extraHeight).dp

    fun tr(ru: String, en: String): String = if (language == "en") en else ru

    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(tr("График показаний", "Readings Chart"), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (readings.isEmpty() || series.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (series.isEmpty()) tr("В настройках выберите входы для графика", "Select chart inputs in Settings")
                        else tr("Нет данных для графика", "No chart data available")
                    )
                }
            } else {
                Canvas(Modifier.fillMaxWidth().height(chartHeight).pointerInput(readings) {
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
                    val unit = if (series.all { it.input in setOf(4, 6, 12) }) "°C" else tr("Значение", "Value")
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
                            "${tr("Срез:", "Slice:")} ${Instant.ofEpochMilli(row.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))}",
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

private fun chartSeries(enabledInputs: Set<Int>, names: Map<Int, String>, defaultNames: List<String>): List<TemperatureSeries> =
    enabledInputs.sorted().map { input -> TemperatureSeries(input, names[input].orEmpty().ifBlank { defaultNames[input - 1] }, inputColors[input - 1]) }
