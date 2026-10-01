package com.ravium.teyeslauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravium.teyeslauncher.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================ shared bits ============================

@Composable
internal fun InputField(value: String, onChange: (String) -> Unit, placeholder: String, number: Boolean = false, modifier: Modifier = Modifier) {
    TextField(
        value = value, onValueChange = onChange,
        modifier = modifier.fillMaxWidth().height(68.dp).clip(CardShape).border(Hairline, C.Stroke, CardShape),
        placeholder = { Text(placeholder, style = t(19f, C.Muted)) },
        textStyle = t(21f, C.Text), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = C.Card, unfocusedContainerColor = C.Card, cursorColor = C.Yellow,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
    )
}

@Composable
internal fun PrimaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().height(60.dp).clip(CardShape).background(if (enabled) C.YellowBg else C.Card)
        .border(1.5.dp, if (enabled) C.YellowBorder else C.Stroke, CardShape).bounceClick(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) { Text(label, style = t(18f, if (enabled) C.Text else C.Muted, FontWeight.Medium)) }
}

@Composable
internal fun ProgressLine(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF22262A))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
    }
}

@Composable
internal fun SmallButton(label: String, onClick: () -> Unit) {
    Box(Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B1E21)).border(Hairline, C.Stroke, RoundedCornerShape(12.dp))
        .bounceClick(onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { Text(label, style = t(15f, C.Text2, FontWeight.Medium)) }
}

@Composable
internal fun IconButtonBox(icon: ImageVector, size: Int = 44, tint: Color = C.Text2, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B1E21)).border(Hairline, C.Stroke, RoundedCornerShape(12.dp))
        .bounceClick(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.5f).dp)) }
}

internal fun weatherIconFor(code: Int): ImageVector = when (code) {
    0, 1 -> Icons.Outlined.WbSunny
    2, 3, 45, 48 -> Icons.Outlined.Cloud
    in 51..67, in 80..82 -> Icons.Outlined.WaterDrop
    in 71..77, 85, 86 -> Icons.Outlined.AcUnit
    in 95..99 -> Icons.Outlined.Thunderstorm
    else -> Icons.Outlined.Cloud
}

// ============================ REMINDERS PAGE ============================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemindersPage(s: LauncherState) {
    val ctx = LocalContext.current
    val odo = s.vehicle.odometerKm.also { s.vehicle.fixVersion }
    val states = Reminders.list.let { Reminders.states(ctx, odo) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1.45f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (states.isEmpty()) {
                Column(Modifier.fillMaxWidth().clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(24.dp)) {
                    Text("Напоминаний пока нет", style = t(22f, C.Text, FontWeight.Medium))
                    Spacer(Modifier.height(8.dp))
                    Text("Добавьте замену масла, ОСАГО или техосмотр справа — лаунчер напомнит заранее. Пробег считается по GPS; " +
                        "один раз укажите пробег с приборной панели, чтобы цифры совпадали.", style = t(16f, C.Muted))
                }
            }
            states.forEach { st -> ReminderCard(s, st, odo) }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RowCard("Пробег: " + Reminders.fmtKm(odo), if (s.vehicle.odometerSet) "Считается по GPS · нажмите, чтобы поправить" else "Нажмите и введите пробег с приборной панели",
                onClick = { s.overlay = Overlay.Odometer }) { Icon(Icons.Outlined.Speed, null, tint = C.Text2, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(8.dp)); Chevron() }
            Spacer(Modifier.height(4.dp))
            Section("Добавить")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Reminders.templates.forEachIndexed { i, tpl ->
                    val exists = Reminders.list.any { it.title == tpl.title }
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF181B1D)).border(1.5.dp, C.Stroke, RoundedCornerShape(12.dp))
                        .clickable { s.overlay = Overlay.ReminderEdit(null, i) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text((if (exists) "✓ " else "+ ") + tpl.title, style = t(15f, if (exists) C.Muted else C.Text2))
                    }
                }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(C.YellowBg).border(1.5.dp, C.YellowBorder, RoundedCornerShape(12.dp))
                    .clickable { s.overlay = Overlay.ReminderEdit(null, -1) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("+ Своё напоминание", style = t(15f, C.Text))
                }
            }
        }
    }
}

@Composable
private fun ReminderCard(s: LauncherState, st: ReminderState, odo: Double) {
    val ctx = LocalContext.current
    val color = when { st.due -> C.GuideRed; st.soon -> C.Yellow; else -> C.GuideGreen }
    Column(Modifier.fillMaxWidth().clip(CardShape).background(CardBrush).border(Hairline, if (st.due) C.GuideRed.copy(alpha = 0.6f) else C.Stroke, CardShape)
        .padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (st.r.byKm) Icons.Outlined.OilBarrel else Icons.Outlined.Event, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(st.r.title, style = t(19f, C.Text, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(st.left + if (st.r.byKm) " · каждые ${Reminders.fmtKm(st.r.intervalKm.toDouble())}" else "", style = t(14f, if (st.due) C.GuideRed else C.Muted))
            }
            SmallButton("Сделано") { Reminders.done(ctx, st.r, odo); Apps.toast(ctx, "Отмечено: ${st.r.title}") }
            Spacer(Modifier.width(8.dp))
            IconButtonBox(Icons.Outlined.Edit) { s.overlay = Overlay.ReminderEdit(st.r.id) }
        }
        Spacer(Modifier.height(10.dp))
        ProgressLine(st.fraction, color)
    }
}

@Composable
fun ReminderEditScreen(s: LauncherState, id: Long?, template: Int) {
    val ctx = LocalContext.current
    val existing = remember { id?.let { i -> Reminders.load(ctx).firstOrNull { it.id == i } } }
    val tpl = Reminders.templates.getOrNull(template)
    val odo = s.vehicle.odometerKm
    var title by remember { mutableStateOf(existing?.title ?: tpl?.title ?: "") }
    var byKm by remember { mutableStateOf(existing?.byKm ?: tpl?.byKm ?: true) }
    var interval by remember { mutableStateOf((existing?.intervalKm ?: tpl?.km ?: 10_000).toString()) }
    var last by remember { mutableStateOf((existing?.lastKm ?: odo).toLong().toString()) }
    var date by remember { mutableStateOf(existing?.dueAt?.takeIf { it > 0 }?.let { SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(it)) } ?: "") }
    var months by remember { mutableStateOf((existing?.intervalMonths ?: tpl?.months ?: 12).toString()) }
    val dateMs = Reminders.parseDate(date)
    val valid = title.isNotBlank() && (if (byKm) (interval.toIntOrNull() ?: 0) > 0 && last.toDoubleOrNull() != null else dateMs != null)
    fun save() {
        val r = Reminder(existing?.id ?: System.currentTimeMillis(), title.trim(), byKm,
            intervalKm = interval.toIntOrNull() ?: 0, lastKm = last.toDoubleOrNull() ?: odo,
            dueAt = dateMs ?: 0L, intervalMonths = months.toIntOrNull() ?: 0)
        Reminders.upsert(ctx, r); s.overlay = null
    }
    Column(Modifier.fillMaxSize()) {
        OverlayHeader(if (existing != null) "Напоминание" else "Новое напоминание", action = if (existing != null) ({
            Pill("Удалить") { Reminders.remove(ctx, existing.id); s.overlay = null }
        }) else null) { s.overlay = null }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Section("Название")
                InputField(title, { title = it.take(40) }, "Например, замена масла")
                ChipsRow("Напоминать", listOf("km" to "По пробегу", "date" to "По дате"), if (byKm) "km" else "date") { byKm = it == "km" }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (byKm) {
                    Section("Каждые, км")
                    InputField(interval, { interval = it.filter(Char::isDigit).take(6) }, "10000", number = true)
                    Section("Последний раз на пробеге, км (сейчас ${odo.toLong()})")
                    InputField(last, { last = it.filter(Char::isDigit).take(7) }, odo.toLong().toString(), number = true)
                } else {
                    Section("Дата окончания (ДД.ММ.ГГГГ)")
                    InputField(date, { date = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, "31.12.2026", number = true)
                    if (date.length == 10 && dateMs == null) Text("Проверьте дату", style = t(14f, C.GuideRed), modifier = Modifier.padding(start = 6.dp))
                    ChipsRow("После «Сделано» продлить на", listOf("6" to "6 мес", "12" to "1 год", "24" to "2 года", "36" to "3 года"), months) { months = it }
                }
                Spacer(Modifier.height(6.dp))
                PrimaryButton("Сохранить", enabled = valid) { save() }
            }
        }
    }
}

@Composable
fun OdometerScreen(s: LauncherState) {
    val ctx = LocalContext.current
    var km by remember { mutableStateOf(s.vehicle.odometerKm.toLong().toString()) }
    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Пробег") { s.overlay = null }
        Text("Введите пробег с приборной панели. Дальше лаунчер сам прибавляет километры по GPS.", style = t(16f, C.Muted),
            modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 14.dp))
        InputField(km, { km = it.filter(Char::isDigit).take(7) }, "123456", number = true)
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Сохранить", enabled = km.toDoubleOrNull() != null) {
            s.vehicle.setOdometer(km.toDouble()); Apps.toast(ctx, "Пробег сохранён"); s.overlay = null
        }
    }
}

// ============================ WEATHER ============================

@Composable
fun WeatherScreen(s: LauncherState) {
    val loc = s.vehicle.location
    var fc by remember { mutableStateOf<Forecast?>(null) }
    var dest by remember { mutableStateOf<Forecast?>(null) }
    var failed by remember { mutableStateOf(false) }
    val route = s.nav.route
    LaunchedEffect(loc == null) {
        if (loc != null) { fc = ForecastRepo.load(loc.latitude, loc.longitude); failed = fc == null }
    }
    LaunchedEffect(route?.destination) { route?.destination?.let { dest = ForecastRepo.load(it.lat, it.lon) } }
    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Погода") { s.overlay = null }
        Spacer(Modifier.height(10.dp))
        val f = fc
        if (f == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    loc == null -> Text("Нет GPS-позиции — погода появится, когда найдутся спутники", style = t(20f, C.Muted))
                    failed -> Text("Нет интернета", style = t(20f, C.Muted))
                    else -> LoaderRow("Загружаю погоду…", color = C.Yellow, textColor = C.Text2)
                }
            }
            return@Column
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(0.9f).fillMaxHeight().clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(weatherIconFor(f.code), null, tint = C.Yellow, modifier = Modifier.size(72.dp))
                    Spacer(Modifier.width(18.dp))
                    Text("${f.temp}°", style = t(88f, C.Text, FontWeight.Light))
                }
                Text(ForecastRepo.describe(f.code), style = t(24f, C.Text, FontWeight.Medium))
                Spacer(Modifier.height(8.dp))
                Text("Ощущается как ${f.feels}° · ветер ${f.windMs} м/с · влажность ${f.humidity}%", style = t(16f, C.Muted))
                Spacer(Modifier.height(16.dp))
                f.warnings.forEach { w ->
                    Row(Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33F0342A)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.WarningAmber, null, tint = Color(0xFFFF8A80), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text(w, style = t(15f, C.Text))
                    }
                }
                val d = dest
                if (route != null && d != null) {
                    Spacer(Modifier.weight(1f))
                    Text("В точке назначения (${route.destination.name})", style = t(14f, C.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(weatherIconFor(d.code), null, tint = C.Text2, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${d.temp}° · ${ForecastRepo.describe(d.code)}" + (d.warnings.firstOrNull()?.let { " · $it" } ?: ""), style = t(18f, C.Text))
                    }
                }
            }
            Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 14.dp)) {
                    f.hours.take(16).forEach { h ->
                        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(h.label, style = t(14f, C.Muted))
                            Spacer(Modifier.height(6.dp))
                            Icon(weatherIconFor(h.code), null, tint = C.Text2, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("${h.temp}°", style = t(19f, C.Text, FontWeight.Medium))
                            Text(if (h.rainPct >= 20) "${h.rainPct}%" else " ", style = t(13f, Color(0xFF6FA8FF)))
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape)
                    .padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    f.days.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.label, style = t(17f, C.Text), modifier = Modifier.width(150.dp))
                            Icon(weatherIconFor(d.code), null, tint = C.Text2, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(ForecastRepo.describe(d.code), style = t(15f, C.Muted), modifier = Modifier.weight(1f), maxLines = 1)
                            Text("${d.min}° … ${d.max}°", style = t(17f, C.Text, FontWeight.Medium))
                        }
                    }
                }
            }
        }
    }
}

// ============================ PARKING ============================

/** Standing still for a while: big clock, weather, trip, next reminder. Tap anywhere to get the map back. */
@Composable
fun ParkingPanel(s: LauncherState, modifier: Modifier, standalone: Boolean = false) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000L - System.currentTimeMillis() % 1000L) } }
    val time = remember(now.time / 60_000) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val date = remember(now.time / 3_600_000) { SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(now).replaceFirstChar { it.titlecase(Locale("ru")) } }
    val next = remember(now.time / 60_000) { Reminders.states(ctx, s.vehicle.odometerKm).firstOrNull() }
    Box(modifier.clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape)
        .clickable(enabled = !standalone) { s.parkDismissed = true; s.parked = false }.padding(36.dp)) {
        Column {
            Text(time, style = t(150f, C.Text, FontWeight.Light).copy(lineHeight = 160.sp))
            Text(date, style = t(26f, C.Text2))
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIconFor(s.weather.code), null, tint = C.Yellow, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Text((s.weather.tempC?.let { "$it°  " } ?: "") + ForecastRepo.describe(s.weather.code), style = t(24f, C.Text))
                s.vehicle.location?.let { l -> Sun.times(l.latitude, l.longitude) }?.let { (r, st) ->
                    Spacer(Modifier.width(28.dp))
                    Icon(Icons.Outlined.WbTwilight, null, tint = C.Muted, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("$r · $st", style = t(18f, C.Muted))
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            val tripKm = s.vehicle.tripM / 1000
            if (tripKm >= 0.5) Text("Поездка: %.1f км за %d мин".format(tripKm, s.vehicle.movingMs / 60_000), style = t(18f, C.Text2))
            next?.let { Text("${it.r.title}: ${it.left}", style = t(18f, if (it.due) C.GuideRed else if (it.soon) C.Yellow else C.Text2)) }
        }
        if (!standalone) Row(Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B1E21)).border(Hairline, C.Stroke, RoundedCornerShape(14.dp))
            .clickable { s.parkDismissed = true; s.parked = false }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Map, null, tint = C.Text2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp)); Text("Карта", style = t(16f, C.Text2, FontWeight.Medium))
        }
    }
}

// ============================ WHEEL KEYS ============================

@Composable
fun WheelScreen(s: LauncherState) {
    val ctx = LocalContext.current
    val v = s.settingsVersion
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(500); tick++ } }
    val on = remember(v, tick / 4) { WheelKeys.enabled(ctx) }
    val last = remember(tick) { WheelKeys.lastKey }
    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Кнопки руля") { s.overlay = Overlay.Settings }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RowCard(if (on) "Жесты включены" else "1. Включите «Minimal Drive — кнопки руля»",
                    if (on) "Обычное нажатие работает как раньше" else "Настройки → Спец. возможности → Minimal Drive — кнопки руля → Вкл.",
                    onClick = { WheelKeys.openSettings(ctx) }) { StatusDot(on); Spacer(Modifier.width(10.dp)); Chevron() }
                RowCard("2. Проверка", if (!on) "Сначала включите службу" else last?.let { "Последняя кнопка: $it" } ?: "Нажмите «следующий трек» на руле…") {
                    Icon(Icons.Outlined.TouchApp, null, tint = if (last != null) C.GuideGreen else C.Muted, modifier = Modifier.size(28.dp))
                }
                Text("Если при нажатии на руле здесь ничего не появляется — магнитола обрабатывает эти кнопки сама, " +
                    "и жесты на ней работать не будут. Кнопки можно переназначить в штатных настройках TEYES («Кнопки руля»).",
                    style = t(14f, C.Muted), modifier = Modifier.padding(horizontal = 6.dp))
                Text("Долгое — удерживать больше 0,6 с. Двойное — два нажатия подряд (обычное нажатие тогда срабатывает с задержкой 0,4 с).",
                    style = t(14f, C.Muted), modifier = Modifier.padding(horizontal = 6.dp))
            }
            Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WheelKeys.gestures.forEach { (g, title) ->
                    val cur = remember(v) { WheelKeys.action(ctx, g) }
                    ChipsRow(title, WheelKeys.actions, cur) { Prefs.put(ctx, Prefs.WHEEL + g, it); s.settingsVersion++ }
                }
            }
        }
    }
}

// ============================ DOCK (bottom bar) ============================

object Dock {
    val builtins = listOf(
        "home" to "Главная", "nav" to "Навигация", "music" to "Музыка", "phone" to "Телефон", "apps" to "Все приложения",
        "carlink" to "CarPlay / CarLink", "settings" to "Настройки", "weather" to "Погода", "search" to "Куда едем",
        "tiles" to "Плитки", "reminders" to "Напоминания", "optimize" to "Оптимизация",
    )
    private const val DEFAULT = "home,nav,music,phone,apps"

    fun load(ctx: android.content.Context): List<String> {
        Prefs.str(ctx, Prefs.DOCK_ITEMS)?.let { return it.split(',').filter { x -> x.isNotBlank() } }
        // first run of this version: keep apps the user assigned to the old three slots
        val items = DEFAULT.split(',').toMutableList()
        listOf(1 to "nav", 2 to "music", 3 to "phone").forEach { (n, id) ->
            Prefs.str(ctx, Prefs.DOCK + n)?.takeIf { Apps.installed(ctx, it) }?.let { items[items.indexOf(id)] = "app=$it" }
        }
        return items
    }
    fun save(ctx: android.content.Context, items: List<String>) = Prefs.put(ctx, Prefs.DOCK_ITEMS, items.joinToString(","))
    fun reset(ctx: android.content.Context) { Prefs.put(ctx, Prefs.DOCK_ITEMS, DEFAULT); (1..3).forEach { Prefs.put(ctx, Prefs.DOCK + it, null) } }

    fun title(ctx: android.content.Context, id: String) =
        if (id.startsWith("app=")) Apps.label(ctx, id.removePrefix("app=")) else builtins.firstOrNull { it.first == id }?.second ?: id

    fun icon(id: String): ImageVector? = when (id) {
        "home" -> Icons.Outlined.Home; "nav" -> Icons.Outlined.NearMe; "phone" -> Icons.Outlined.Phone
        "carlink" -> Icons.Outlined.PhoneIphone; "settings" -> Icons.Outlined.Settings; "weather" -> Icons.Outlined.WbSunny
        "search" -> Icons.Outlined.Search; "tiles" -> Icons.Outlined.Dashboard; "reminders" -> Icons.Outlined.EventNote
        "optimize" -> Icons.Outlined.Memory
        else -> null   // music / apps have their own glyphs, apps show their icon
    }

    fun open(s: LauncherState, id: String) {
        val ctx = s.activity
        when {
            id.startsWith("app=") -> { s.overlay = null; Apps.launch(ctx, id.removePrefix("app=")) }
            id == "home" -> { s.overlay = null; s.go(Page.HOME) }
            id == "nav" -> s.openNavigator()
            id == "music" -> s.media.openSourceApp()
            id == "phone" -> s.launchAssigned(Prefs.PHONE, Known.PHONE, "Выберите приложение телефона")
            id == "apps" -> s.overlay = Overlay.Drawer
            id == "carlink" -> s.launchAssigned(Prefs.CARLINK, Known.CARLINK, "Выберите приложение CarPlay / CarLink")
            id == "settings" -> s.overlay = Overlay.Settings
            id == "weather" -> s.overlay = Overlay.Weather
            id == "search" -> s.overlay = Overlay.Search()
            id == "tiles" -> { s.overlay = null; s.go(Page.TILES) }
            id == "reminders" -> { s.overlay = null; s.go(Page.REMINDERS) }
            id == "optimize" -> { s.overlay = null; s.go(Page.OPTIMIZE) }
        }
    }

    fun selected(s: LauncherState, id: String) = s.overlay == null && when (id) {
        "home" -> s.page == Page.HOME; "tiles" -> s.page == Page.TILES; "reminders" -> s.page == Page.REMINDERS; "optimize" -> s.page == Page.OPTIMIZE
        else -> false
    }
}

@Composable
internal fun DockGlyph(id: String, size: Int = 30, tint: Color = C.Text2) {
    val ctx = LocalContext.current
    when {
        id.startsWith("app=") -> {
            val ic = remember(id) { Apps.icon(ctx, id.removePrefix("app=")) }
            if (ic != null) Image(ic, null, Modifier.size((size + 4).dp).clip(RoundedCornerShape(9.dp)), filterQuality = FilterQuality.High)
            else Icon(Icons.Outlined.Android, null, tint = tint, modifier = Modifier.size(size.dp))
        }
        id == "music" -> MusicNotesIcon(size.dp, tint)
        id == "apps" -> GridIcon((size - 2).dp, tint)
        else -> Icon(Dock.icon(id) ?: Icons.Outlined.Apps, null, tint = tint, modifier = Modifier.size(size.dp))
    }
}

@Composable
fun DockScreen(s: LauncherState) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(Dock.load(ctx)) }
    fun set(l: List<String>) { items = l; Dock.save(ctx, l); s.settingsVersion++ }
    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Нижняя панель", action = { Pill("По умолчанию") { Dock.reset(ctx); items = Dock.load(ctx); s.settingsVersion++ } }) { s.overlay = Overlay.SettingsCat("look") }
        Text("До 8 кнопок. Стрелки меняют порядок. Долгое нажатие на кнопку внизу тоже открывает этот экран.", style = t(15f, C.Muted),
            modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 12.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section("Сейчас на панели")
                items.forEachIndexed { i, id ->
                    Row(Modifier.fillMaxWidth().height(68.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { DockGlyph(id, 28) }
                        Spacer(Modifier.width(14.dp))
                        Text(Dock.title(ctx, id), style = t(18f, C.Text, FontWeight.Medium), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButtonBox(Icons.Outlined.KeyboardArrowUp) { if (i > 0) set(items.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                        Spacer(Modifier.width(6.dp))
                        IconButtonBox(Icons.Outlined.KeyboardArrowDown) { if (i < items.size - 1) set(items.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                        Spacer(Modifier.width(6.dp))
                        IconButtonBox(Icons.Outlined.Close) { if (items.size > 1) set(items - id) else Apps.toast(ctx, "Нужна хотя бы одна кнопка") }
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section("Добавить")
                fun add(id: String) { if (items.size >= 8) Apps.toast(ctx, "Максимум 8 кнопок") else set(items + id) }
                RowCard("Приложение…", "Любое установленное приложение с иконкой", onClick = {
                    s.pick("Добавить на панель") { p -> add("app=$p"); s.overlay = Overlay.Dock }
                }) { Icon(Icons.Outlined.Add, null, tint = C.Yellow, modifier = Modifier.size(28.dp)) }
                Dock.builtins.filter { it.first !in items }.forEach { (id, title) ->
                    RowCard(title, onClick = { add(id) }) {
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { DockGlyph(id, 26, C.Muted) }
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Outlined.Add, null, tint = C.Yellow, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}

// ============================ HOME LAYOUT ============================

/** What the main screen shows: a big area (map or clock) and a side column of blocks (music, service bar, any tile). */
object HomeLayout {
    fun blocks(ctx: android.content.Context) = Prefs.str(ctx, Prefs.HOME_BLOCKS, "music,service").split(',').filter { it.isNotBlank() }
    fun saveBlocks(ctx: android.content.Context, l: List<String>) = Prefs.put(ctx, Prefs.HOME_BLOCKS, l.joinToString(","))
    fun sideLeft(ctx: android.content.Context) = Prefs.str(ctx, Prefs.HOME_SIDE, "left") == "left"
    fun bigMap(ctx: android.content.Context) = Prefs.str(ctx, Prefs.HOME_BIG, "map") == "map"
    fun wide(ctx: android.content.Context) = Prefs.bool(ctx, Prefs.HOME_WIDE, false)
    fun reset(ctx: android.content.Context) { listOf(Prefs.HOME_BLOCKS, Prefs.HOME_SIDE, Prefs.HOME_BIG).forEach { Prefs.put(ctx, it, null) }; Prefs.put(ctx, Prefs.HOME_WIDE, false) }

    fun title(ctx: android.content.Context, id: String): String = when {
        id == "music" -> "Музыка"
        id == "service" -> "Панель: приложения · CarPlay · настройки"
        id.startsWith("tile=app:") -> Apps.label(ctx, id.removePrefix("tile=app:"))
        id.startsWith("tile=") -> TileCatalog.def(id.removePrefix("tile="))?.title ?: id
        else -> id
    }
}

@Composable
fun HomeLayoutScreen(s: LauncherState) {
    val ctx = LocalContext.current
    val v = s.settingsVersion
    var blocks by remember(v) { mutableStateOf(HomeLayout.blocks(ctx)) }
    fun set(l: List<String>) { blocks = l; HomeLayout.saveBlocks(ctx, l); s.settingsVersion++ }
    fun canAdd(id: String): Boolean {
        val music = "music" in blocks || id == "music"
        val limit = if (music) 3 else 5
        if (blocks.size >= limit) { Apps.toast(ctx, if (music) "С музыкой помещается ещё 2 блока" else "Максимум 5 блоков"); return false }
        return true
    }
    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Главный экран", action = { Pill("По умолчанию") { HomeLayout.reset(ctx); s.settingsVersion++ } }) { s.overlay = Overlay.SettingsCat("look") }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChipsRow("Большой блок", listOf("map" to "Карта", "clock" to "Часы и погода"), if (HomeLayout.bigMap(ctx)) "map" else "clock",
                    "Карта — как обычно. «Часы и погода» — крупные часы вместо карты (карта тогда не грузится вовсе, магнитоле легче).") {
                    Prefs.put(ctx, Prefs.HOME_BIG, it); s.settingsVersion++
                }
                ChipsRow("Колонка блоков", listOf("left" to "Слева", "right" to "Справа"), if (HomeLayout.sideLeft(ctx)) "left" else "right",
                    "С какой стороны от большого блока стоит колонка с музыкой и другими блоками. Справа удобнее, если руль слева и до экрана далеко тянуться.") {
                    Prefs.put(ctx, Prefs.HOME_SIDE, it); s.settingsVersion++
                }
                ChipsRow("Ширина колонки", listOf("n" to "Обычная", "w" to "Широкая"), if (HomeLayout.wide(ctx)) "w" else "n") {
                    Prefs.put(ctx, Prefs.HOME_WIDE, it == "w"); s.settingsVersion++
                }
                Section("Блоки в колонке (сверху вниз)")
                blocks.forEachIndexed { i, id ->
                    Row(Modifier.fillMaxWidth().height(64.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(HomeLayout.title(ctx, id), style = t(17f, C.Text, FontWeight.Medium), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButtonBox(Icons.Outlined.KeyboardArrowUp) { if (i > 0) set(blocks.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                        Spacer(Modifier.width(6.dp))
                        IconButtonBox(Icons.Outlined.KeyboardArrowDown) { if (i < blocks.size - 1) set(blocks.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                        Spacer(Modifier.width(6.dp))
                        IconButtonBox(Icons.Outlined.Close) { if (blocks.size > 1) set(blocks - id) else Apps.toast(ctx, "Нужен хотя бы один блок") }
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section("Добавить блок")
                listOf("music", "service").filter { it !in blocks }.forEach { id ->
                    RowCard(HomeLayout.title(ctx, id), onClick = { if (canAdd(id)) set(blocks + id) }) { Icon(Icons.Outlined.Add, null, tint = C.Yellow, modifier = Modifier.size(26.dp)) }
                }
                TileCatalog.defs.filter { it.sizes.contains(2 to 1) && "tile=${it.type}" !in blocks }.forEach { d ->
                    RowCard(d.title, d.desc, onClick = {
                        if (!canAdd("tile=${d.type}")) return@RowCard
                        if (d.type == "app") s.pick("Приложение на главном экране") { p -> set(blocks + "tile=app:$p"); s.overlay = Overlay.HomeLayout }
                        else set(blocks + "tile=${d.type}")
                    }) {
                        Icon(d.icon, null, tint = C.Muted, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(10.dp))
                        Icon(Icons.Outlined.Add, null, tint = C.Yellow, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}

// ============================ OFFLINE MAPS ============================

@Composable
fun OfflineScreen(s: LauncherState) {
    val ctx = LocalContext.current
    val ver = OfflineMaps.version
    var query by remember { mutableStateOf("") }
    val loc = s.vehicle.location
    LaunchedEffect(Unit) {
        OfflineMaps.start(); OfflineMaps.refreshSize()
        loc?.let { OfflineMaps.findNearby(it.latitude, it.longitude) }
    }
    val all = remember(ver) { OfflineMaps.regions() }
    val routeStatus = s.vehicle.limits.routeStatus
    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Карты без интернета") { s.overlay = null }
        Spacer(Modifier.height(10.dp))
        if (!OfflineMaps.available) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.fillMaxWidth().clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(22.dp)) {
                        Text("Скачивание работает с картой Яндекса", style = t(22f, C.Text, FontWeight.Medium))
                        Spacer(Modifier.height(8.dp))
                        Text("Введите бесплатный ключ Яндекс MapKit — и можно будет заранее скачать свою область: карта, маршруты и поиск " +
                            "будут работать без интернета.\n\nСейчас (OpenStreetMap) лаунчер сохраняет всё, что уже было на экране (до 300 МБ), " +
                            "а ограничения скорости и камеры загружает вдоль всего маршрута, пока есть интернет.", style = t(16f, C.Text2))
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("Ввести ключ Яндекса") { s.overlay = Overlay.ApiKey }
                    }
                }
                Column(Modifier.weight(1f)) { routeStatus?.let { RowCard(it, "Лаунчер загружает их сам при построении маршрута") { Icon(Icons.Outlined.Route, null, tint = C.Text2, modifier = Modifier.size(26.dp)) } } }
            }
            return@Column
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // ---- left: what's saved, nearby regions
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RowCard("Сохранено на магнитоле: " + (OfflineMaps.cacheSize ?: "…"),
                    info = "Скачанная область работает без интернета: карта, маршруты и поиск. Обновляется сама, когда есть Wi-Fi или интернет с телефона. " +
                        "Для дальней поездки скачайте все области по пути заранее.") {
                    Icon(Icons.Outlined.SdStorage, null, tint = C.Text2, modifier = Modifier.size(26.dp))
                }
                RowCard(routeStatus ?: "Ограничения скорости на маршруте", if (routeStatus == null) "Загрузятся сами, когда построите маршрут" else "Будут работать и без интернета",
                    info = "Когда вы строите маршрут, лаунчер сразу загружает ограничения скорости и камеры вдоль всего пути, пока есть интернет.") {
                    Icon(Icons.Outlined.Speed, null, tint = C.Text2, modifier = Modifier.size(26.dp))
                }
                val near = all.filter { it.id in OfflineMaps.nearby }
                if (near.isNotEmpty()) { Section("Где вы сейчас"); near.forEach { RegionRow(it, ver) } }
                val saved = all.filter { OfflineMaps.state(it.id) in setOf(com.yandex.mapkit.offline_cache.RegionState.COMPLETED,
                    com.yandex.mapkit.offline_cache.RegionState.DOWNLOADING, com.yandex.mapkit.offline_cache.RegionState.PAUSED,
                    com.yandex.mapkit.offline_cache.RegionState.OUTDATED, com.yandex.mapkit.offline_cache.RegionState.NEED_UPDATE) && it.id !in OfflineMaps.nearby }
                if (saved.isNotEmpty()) { Section("Скачанные"); saved.forEach { RegionRow(it, ver) } }
                if (all.isEmpty()) Text("Список областей загружается — нужен интернет…", style = t(16f, C.Muted), modifier = Modifier.padding(6.dp))
            }
            // ---- right: search all regions
            Column(Modifier.weight(1f).fillMaxHeight()) {
                InputField(query, { query = it }, "Найти область или город")
                Spacer(Modifier.height(10.dp))
                val q = query.trim().lowercase()
                val list = remember(ver, q) {
                    (if (q.length < 2) all.filter { it.country.contains("Росс") } else all.filter { it.name.lowercase().contains(q) || it.country.lowercase().contains(q) })
                        .sortedBy { it.name }.take(80)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { RegionRow(it, ver) }
                    if (list.isEmpty() && all.isNotEmpty()) Text("Ничего не найдено", style = t(16f, C.Muted), modifier = Modifier.padding(6.dp))
                }
            }
        }
    }
}

@Composable
private fun RegionRow(r: com.yandex.mapkit.offline_cache.Region, ver: Int) {
    val st = remember(ver, r.id) { OfflineMaps.state(r.id) }
    val prog = remember(ver, r.id) { OfflineMaps.progress(r.id) }
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape)
        .padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.name, style = t(17f, C.Text, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(r.country.takeIf { it.isNotBlank() }, r.size?.text).joinToString(" · "), style = t(13f, C.Muted), maxLines = 1)
            if (st == com.yandex.mapkit.offline_cache.RegionState.DOWNLOADING || st == com.yandex.mapkit.offline_cache.RegionState.PAUSED)
                ProgressLine(prog, C.Yellow, Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.width(10.dp))
        when (st) {
            com.yandex.mapkit.offline_cache.RegionState.DOWNLOADING -> SmallButton("${(prog * 100).toInt()}% · пауза") { OfflineMaps.pause(r.id) }
            com.yandex.mapkit.offline_cache.RegionState.PAUSED -> SmallButton("Продолжить") { OfflineMaps.download(r.id) }
            com.yandex.mapkit.offline_cache.RegionState.COMPLETED -> SmallButton(if (confirm) "Точно удалить?" else "✓ Скачано") {
                if (confirm) { OfflineMaps.drop(r.id); confirm = false } else confirm = true
            }
            com.yandex.mapkit.offline_cache.RegionState.OUTDATED, com.yandex.mapkit.offline_cache.RegionState.NEED_UPDATE -> SmallButton("Обновить") { OfflineMaps.download(r.id) }
            com.yandex.mapkit.offline_cache.RegionState.UNSUPPORTED -> Text("недоступно", style = t(14f, C.Muted))
            else -> SmallButton("Скачать") { OfflineMaps.download(r.id) }
        }
    }
}
