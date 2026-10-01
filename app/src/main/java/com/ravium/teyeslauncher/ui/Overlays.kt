package com.ravium.teyeslauncher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravium.teyeslauncher.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun t(size: Float, color: Color = C.Text, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, color = color, fontWeight = weight, lineHeight = (size * 1.25f).sp)

@Composable
fun BoxScope.Overlays(s: LauncherState) {
    val o = s.overlay
    if (InstantUi && o == null) return
    // Search is special: it does NOT cover the map — list on one side, the live map stays on the other.
    if (o is Overlay.Search) { SearchPanel(s, o.setHome); return }
    AnimatedVisibility(o != null && o !is Overlay.Search, enter = if (InstantUi) androidx.compose.animation.EnterTransition.None else fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier.fillMaxSize().background(Color(0xF20A0C0D))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(horizontal = 33.dp, vertical = 28.dp)
        ) {
            when (o) {
                is Overlay.Drawer -> AppGrid(s, "Приложения", drawer = true) { pkg -> s.overlay = null; Apps.launch(s.activity, pkg) }
                is Overlay.Picker -> AppGrid(s, o.title, drawer = false, onReset = o.onReset?.let { r -> { s.overlay = null; r() } }) { pkg ->
                    s.overlay = null; o.onPick(pkg)
                }
                is Overlay.Settings -> SettingsHome(s)
                is Overlay.SettingsCat -> SettingsCategory(s, o.id)
                is Overlay.Setup -> SetupScreen(s)
                is Overlay.Diagnostics -> DiagnosticsScreen(s)
                is Overlay.Search -> {}   // отрисовывается отдельно (SearchPanel) — не поверх карты
                is Overlay.ApiKey -> ApiKeyScreen(s)
                is Overlay.WeatherKey -> WeatherKeyScreen(s)
                is Overlay.ProxyKey -> ProxyKeyScreen(s)
                is Overlay.Welcome -> WelcomeScreen(s)
                is Overlay.Favorites -> FavoritesScreen(s)
                is Overlay.Weather -> WeatherScreen(s)
                is Overlay.Dock -> DockScreen(s)
                is Overlay.HomeLayout -> HomeLayoutScreen(s)
                is Overlay.Offline -> OfflineScreen(s)
                is Overlay.Wheel -> WheelScreen(s)
                is Overlay.TileCatalog -> TileCatalogScreen(s)
                is Overlay.Odometer -> OdometerScreen(s)
                is Overlay.ReminderEdit -> ReminderEditScreen(s, o.id, o.template)
                null -> {}
            }
        }
    }
}

@Composable
internal fun OverlayHeader(title: String, action: (@Composable RowScope.() -> Unit)? = null, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = t(30f, C.Text, FontWeight.Medium))
        Spacer(Modifier.weight(1f))
        action?.invoke(this)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(56.dp).clip(CircleShape).background(C.Card).border(Hairline, C.Stroke, CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Close, null, tint = C.Text, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
internal fun Pill(label: String, onClick: () -> Unit) {
    Box(Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(C.Card).border(Hairline, C.Stroke, RoundedCornerShape(28.dp))
        .clickable(onClick = onClick).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) { Text(label, style = t(16f, C.Text2, FontWeight.Medium)) }
}

// ============================ APPS ============================

/** Drawer: favourites (long-press to star), recent, all. Picker: all apps + optional "По умолчанию". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGrid(s: LauncherState, title: String, drawer: Boolean, onReset: (() -> Unit)? = null, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var favs by remember { mutableStateOf(Prefs.list(ctx, Prefs.FAVORITES)) }
    val recents = remember { Prefs.list(ctx, Prefs.RECENTS) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { Apps.all(ctx) } }
    Column(Modifier.fillMaxSize()) {
        OverlayHeader(title, action = if (onReset != null) ({ Pill("По умолчанию", onReset) }) else null) { s.overlay = null }
        if (drawer) Text("Долгое нажатие — добавить в избранное", style = t(14f, C.Muted), modifier = Modifier.padding(start = 6.dp, top = 2.dp))
        Spacer(Modifier.height(14.dp))
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Загрузка…", style = t(18f, C.Muted)) }
            return@Column
        }
        val byPkg = list.associateBy { it.pkg }
        val favApps = favs.mapNotNull { byPkg[it] }
        val recentApps = recents.mapNotNull { byPkg[it] }.filter { it.pkg !in favs }.take(6)
        LazyVerticalGrid(GridCells.Adaptive(150.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            fun header(text: String) = item(span = { GridItemSpan(maxLineSpan) }, key = "h_$text") {
                Text(text.uppercase(), style = t(13f, C.Muted, FontWeight.Medium).copy(letterSpacing = 1.5.sp), modifier = Modifier.padding(start = 6.dp, top = 6.dp))
            }
            fun tiles(section: String, entries: List<AppEntry>) = items(entries, key = { "${section}_${it.pkg}" }) { app ->
                AppTile(app, starred = app.pkg in favs, onClick = { onPick(app.pkg) }, onLongClick = if (drawer) ({
                    favs = if (app.pkg in favs) favs - app.pkg else favs + app.pkg
                    Prefs.putList(ctx, Prefs.FAVORITES, favs)
                }) else null)
            }
            if (drawer && favApps.isNotEmpty()) { header("Избранное"); tiles("f", favApps) }
            if (drawer && recentApps.isNotEmpty()) { header("Недавние"); tiles("r", recentApps) }
            if (drawer && (favApps.isNotEmpty() || recentApps.isNotEmpty())) header("Все приложения")
            tiles("a", list)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: AppEntry, starred: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Box {
        Column(
            Modifier.fillMaxWidth().clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 18.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val ic = app.icon
            if (ic != null) Image(ic, null, Modifier.size(72.dp), filterQuality = FilterQuality.High) else Icon(Icons.Outlined.Android, null, tint = C.Muted, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(12.dp))
            Text(app.label, style = t(15f, C.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        if (starred) Icon(Icons.Filled.Star, null, tint = C.Yellow, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(20.dp))
    }
}

// ============================ SETTINGS ============================

@Composable
internal fun UpdateRow(s: LauncherState, info: String? = null) {
    val u = s.updater
    val rel = u.available
    RowCard(
        if (rel != null) "Установить обновление ${rel.version}" else "Обновления",
        u.status.ifEmpty { "Версия ${BuildConfig.VERSION_NAME} · нажмите, чтобы проверить" },
        onClick = { if (rel != null) u.install() else u.check() }, info = info
    ) {
        if (u.busy) Text("…", style = t(18f, C.Muted)) else if (rel != null) StatusDot(false, C.Yellow)
        Spacer(Modifier.width(10.dp)); Chevron()
    }
}

// ============================ SETUP ============================

@Composable
fun SetupScreen(s: LauncherState) {
    val ctx = LocalContext.current
    val steps = remember(s.settingsVersion) { Permissions.steps(ctx) }
    var hint by remember { mutableStateOf<Permissions.Step?>(null) }
    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Настройка магнитолы") { s.overlay = null }
        Text("Нажмите на пункт → включите Minimal Drive → вернитесь кнопкой «Назад». Зелёная точка — готово.",
            style = t(16f, C.Muted), modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 14.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            steps.forEachIndexed { i, st ->
                RowCard("${i + 1}. ${st.title}", st.why, onClick = {
                    if (!st.open(ctx)) hint = st   // screen missing on this firmware → show adb way
                }) {
                    Text(if (st.done) "Готово" else "Включить", style = t(15f, if (st.done) C.GuideGreen else C.Yellow, FontWeight.Medium))
                    Spacer(Modifier.width(10.dp)); StatusDot(st.done); Spacer(Modifier.width(6.dp)); Chevron()
                }
            }
            val h = hint
            if (h != null) {
                Column(Modifier.fillMaxWidth().clip(CardShape).background(Color(0xFF1B1710)).border(1.5.dp, C.YellowBorder, CardShape).padding(20.dp)) {
                    Text("На этой магнитоле нет экрана «${h.title}»", style = t(18f, C.Text, FontWeight.Medium))
                    Spacer(Modifier.height(6.dp))
                    Text("Выполните команду на компьютере (Android Studio → Terminal):", style = t(15f, C.Muted))
                    Spacer(Modifier.height(8.dp))
                    Text(h.adb, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = C.Yellow))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(64.dp).clip(CardShape).background(C.YellowBg).border(1.5.dp, C.YellowBorder, CardShape)
            .clickable {
                if (!steps.all { it.done }) Prefs.put(ctx, Prefs.SETUP_SNOOZE, (System.currentTimeMillis() + 86_400_000L).toString())
                s.overlay = null
            }, contentAlignment = Alignment.Center) {
            Text(if (steps.all { it.done }) "Готово" else "Позже", style = t(18f, C.Text, FontWeight.Medium))
        }
    }
}

// ============================ DIAGNOSTICS ============================

@Composable
private fun DiagnosticsScreen(s: LauncherState) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var services by remember { mutableStateOf<List<String>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val sections = remember(tick) {
        val dm = ctx.resources.displayMetrics
        val loc = s.vehicle.location
        val fixAge = if (s.vehicle.lastFixAt == 0L) "нет фикса" else "${(SystemClock.elapsedRealtime() - s.vehicle.lastFixAt) / 1000} с назад"
        listOf(
            "Устройство" to listOf(
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "Экран ${dm.widthPixels}×${dm.heightPixels}, density ${dm.density}",
                "Minimal Drive ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            ),
            "Разрешения" to Permissions.steps(ctx).map { (if (it.done) "✓ " else "✗ ") + it.title },
            "Ошибки приложения" to CrashLog.read(ctx).lines().take(8).ifEmpty { listOf("") }.let { l -> if (l.all { it.isBlank() }) listOf("ошибок не было") else l },
            "Интернет-сервисы (нажмите «Проверить сервисы»)" to services.ifEmpty { listOf(if (checking) "проверка…" else "не проверялись") },
            "Карта" to listOf("Движок: " + (if (YandexMaps.enabled) "Яндекс MapKit" else "OpenStreetMap (Яндекс: ${YandexMaps.error ?: "—"})"),
                "Маршруты и поиск: ${s.nav.provider.name}", "Дом: ${s.nav.home?.name ?: "не задан"}"),
            "Bluetooth-музыка" to listOf("Приложение магнитолы: ${BtAudio.describe(ctx)}", "Нажмите «Выбрать BT-приложение», если определилось неверно"),
            "Плееры (сессии)" to s.media.debug(),
            "Навигатор" to listOf(NavInfo.hint?.let { "${it.pkg}: ${it.title} · ${it.text} · ${it.sub}" } ?: "подсказок нет"),
            "GPS" to listOf(
                if (loc == null) "позиции нет" else "%.5f, %.5f · точность %.0f м · %s".format(loc.latitude, loc.longitude, loc.accuracy, fixAge),
                s.vehicle.debug(),
                "Скорость ${s.vehicle.speedKmh} км/ч · курс ${s.vehicle.bearing.toInt()}° · ночь: ${s.night}",
                "Ограничения: ${s.vehicle.limits.debug()}",
            ),
            "Лаунчер" to listOf(
                "По умолчанию: ${if (Kiosk.isDefaultHome(ctx)) "Minimal Drive" else "другой"}",
                "Другие лаунчеры: ${Kiosk.otherLaunchers(ctx).joinToString().ifEmpty { "нет" }}",
                "Последние приложения на экране: ${LauncherGuard.history.joinToString().ifEmpty { "нет данных (нужен доступ к истории)" }}",
            ),
        )
    }
    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Диагностика", action = {
            Pill("Проверить сервисы") { checking = true; services = emptyList(); ServiceCheck.run { services = it; checking = false } }
            Spacer(Modifier.width(10.dp))
            Pill("Выбрать BT-приложение") {
                s.pick("Bluetooth-музыка магнитолы", onReset = { Prefs.put(ctx, Prefs.BT_MUSIC, null); s.overlay = Overlay.Diagnostics }) { p ->
                    Prefs.put(ctx, Prefs.BT_MUSIC, p); s.overlay = Overlay.Diagnostics
                }
            }
            Spacer(Modifier.width(10.dp))
            Pill("Отправить отчёт") {
                val text = "Minimal Drive — отчёт\n\n" + sections.joinToString("\n\n") { (h, l) -> "$h\n" + l.joinToString("\n") } +
                    "\n\n--- Журнал ошибок ---\n" + CrashLog.read(ctx).ifBlank { "пусто" }
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Minimal Drive ${BuildConfig.VERSION_NAME}")
                    .putExtra(Intent.EXTRA_TEXT, text.take(90_000))
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("report", text.take(90_000)))
                if (!Apps.openFirst(ctx, Intent.createChooser(send, "Отправить отчёт"))) Apps.toast(ctx, "Отчёт скопирован — вставьте его в сообщение разработчику")
            }
            Spacer(Modifier.width(10.dp))
            Pill("Скопировать") {
                val text = sections.joinToString("\n\n") { (h, l) -> "$h\n" + l.joinToString("\n") }
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("diag", text))
                Apps.toast(ctx, "Скопировано")
            }
        }) { s.overlay = Overlay.SettingsCat("base") }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sections.forEach { (title, lines) ->
                Column(Modifier.fillMaxWidth().clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(title, style = t(17f, C.Text, FontWeight.Medium))
                    Spacer(Modifier.height(6.dp))
                    lines.forEach { Text(it, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = C.Text2, lineHeight = 18.sp)) }
                }
            }
        }
    }
}

// ============================ OPTIMIZE ============================

@Composable
fun OptimizePage(s: LauncherState) {
    val ctx = LocalContext.current
    val v = s.settingsVersion
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    val mem = remember(tick) { Optimizer.mem(ctx) }
    val disk = remember(v) { Optimizer.storage() }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(Optimizer.lastResult) }
    var autostart by remember { mutableStateOf<List<Optimizer.Candidate>?>(null) }
    var unused by remember { mutableStateOf<List<Optimizer.Candidate>?>(null) }
    LaunchedEffect(v) {   // re-read after returning from an app's Android page
        autostart = withContext(Dispatchers.IO) { Optimizer.autostartApps(ctx) }
        unused = withContext(Dispatchers.IO) { Optimizer.unusedApps(ctx) }
    }
    fun clean() { busy = true; Optimizer.clean(ctx, s.media.sessionPackages()) { result = it; busy = false; tick++ } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // ---- left: state + switches + animations
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.fillMaxWidth().clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Память", style = t(18f, C.Text, FontWeight.Medium))
                        Spacer(Modifier.weight(1f))
                        Text("${mem.usedMb} / ${mem.totalMb} МБ", style = t(16f, C.Text2))
                    }
                    Spacer(Modifier.height(10.dp))
                    val frac = mem.usedFraction.coerceIn(0f, 1f)
                    val barColor = when { frac > 0.88f -> C.GuideRed; frac > 0.75f -> C.Yellow; else -> C.GuideGreen }
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF22262A))) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(barColor))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Свободно ${mem.availMb} МБ" + (if (mem.low) " · памяти мало" else "") + (result?.let { " · $it" } ?: ""), style = t(14f, C.Muted))
                    Spacer(Modifier.height(4.dp))
                    val (free, total) = disk
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(C.YellowBg).border(1.5.dp, C.YellowBorder, RoundedCornerShape(14.dp))
                        .clickable { if (!busy) clean() }, contentAlignment = Alignment.Center) {
                        Text(if (busy) "Очищаю…" else "Освободить память", style = t(17f, C.Text, FontWeight.Medium))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Хранилище: свободно %.1f из %.0f ГБ".format(free, total) + if (total > 0 && free / total < 0.1f) " — почти заполнено, это замедляет систему" else "",
                        style = t(14f, if (total > 0 && free / total < 0.1f) C.Yellow else C.Muted))
                }
                SwitchRow("Автоочистка памяти", remember(v) { Prefs.bool(ctx, Prefs.AUTO_CLEAN, true) }, "При включении зажигания, каждые 30 минут и когда памяти мало. Плеер, навигатор, телефон и CarLink не трогаются.") { Prefs.put(ctx, Prefs.AUTO_CLEAN, it); s.settingsVersion++ }
                SwitchRow("Лёгкий режим карты", remember(v) { Prefs.bool(ctx, Prefs.LITE_MAP, false) }, "Без 3D-наклона и пробок, карта сдвигается реже. Помогает, если карта дёргается.") { Prefs.put(ctx, Prefs.LITE_MAP, it); s.settingsVersion++ }
                Spacer(Modifier.height(6.dp))
                Section("Анимации системы")
                val scale = remember(v) { Optimizer.animationScale(ctx) }
                val devOn = remember(v) { Optimizer.developerOptionsOn(ctx) }
                RowCard(
                    "Скорость анимаций: " + (if (scale == 0f) "выкл" else "${scale}×"),
                    if (scale <= 0.5f) "Уже ускорено — отлично" else if (devOn) "Нажмите → «Анимация окон», «Анимация переходов», «Длительность анимации» → 0,5×"
                    else "Сначала включите «Для разработчиков» (кнопка ниже)",
                    onClick = { Optimizer.openDeveloperOptions(ctx) }
                ) { StatusDot(scale <= 0.5f, C.Yellow); Spacer(Modifier.width(10.dp)); Chevron() }
                if (!devOn) RowCard("Включить «Для разработчиков»", "О устройстве → 7 раз нажать «Номер сборки», затем вернуться сюда",
                    onClick = { Optimizer.openAboutPhone(ctx) }) { Chevron() }
            }
            // ---- right: apps to stop / disable
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Нажмите на приложение → «Остановить», а лишнее — «Отключить» или «Удалить». Вернуть можно там же.",
                    style = t(14f, C.Muted), modifier = Modifier.padding(horizontal = 6.dp))
                Section("Запускаются сами")
                CandidateList(autostart, "Лишнего автозапуска нет") { Optimizer.openApp(ctx, it) }
                Spacer(Modifier.height(6.dp))
                Section("Не открывались 30 дней")
                if (!Kiosk.hasUsageAccess(ctx)) RowCard("Нужен доступ к истории использования", "Нажмите, чтобы выдать", onClick = { Permissions.openUsage(ctx) }) { Chevron() }
                else CandidateList(unused, "Все приложения используются") { Optimizer.openApp(ctx, it) }
            }
        }
    }
}

@Composable
internal fun CandidateList(list: List<Optimizer.Candidate>?, empty: String, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    when {
        list == null -> Text("Загрузка…", style = t(15f, C.Muted), modifier = Modifier.padding(6.dp))
        list.isEmpty() -> Text(empty, style = t(15f, C.Muted), modifier = Modifier.padding(6.dp))
        else -> list.take(30).forEach { c ->
            RowCard(c.label, if (c.system) "${c.reason} · системное — можно отключить" else c.reason, onClick = { onOpen(c.pkg) }) {
                val ic = remember(c.pkg) { Apps.icon(ctx, c.pkg) }
                if (ic != null) Image(ic, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), filterQuality = FilterQuality.High)
                Spacer(Modifier.width(8.dp)); Chevron()
            }
        }
    }
}

// ============================ building blocks ============================

@Composable internal fun Section(title: String) = Text(title.uppercase(), style = t(13f, C.Muted, FontWeight.Medium).copy(letterSpacing = 1.5.sp), modifier = Modifier.padding(start = 6.dp, top = 4.dp))

@Composable internal fun StatusDot(ok: Boolean, bad: Color = C.GuideRed) = Box(Modifier.size(12.dp).clip(CircleShape).background(if (ok) C.GuideGreen else bad))

/** Small «i» button: tap → the description of the setting unfolds below it. */
@Composable
internal fun InfoDot(open: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(start = 8.dp).size(30.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (open) C.Yellow else Color(0xFF2A2E31)), contentAlignment = Alignment.Center) {
            Text("i", style = t(14f, if (open) Color(0xFF111111) else C.Text2, FontWeight.SemiBold).copy(fontFamily = FontFamily.Serif))
        }
    }
}

@Composable
internal fun InfoText(text: String) {
    Text(text, style = t(14.5f, C.Text2).copy(lineHeight = 20.sp), modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
        .clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1D20)).padding(horizontal = 14.dp, vertical = 10.dp))
}

@Composable
internal fun RowCard(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, info: String? = null, trailing: @Composable RowScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = t(18f, C.Text, FontWeight.Medium), modifier = Modifier.weight(1f, fill = false))
                    if (info != null) InfoDot(open) { open = !open }
                }
                if (subtitle != null) { Spacer(Modifier.height(3.dp)); Text(subtitle, style = t(14f, C.Muted), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            trailing()
        }
        if (open && info != null) InfoText(info)
    }
}

@Composable internal fun Chevron() = Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = C.Muted, modifier = Modifier.size(28.dp))

@Composable
internal fun AppRow(s: LauncherState, v: Int, title: String, key: String, known: List<String>, info: String? = null, back: Overlay = Overlay.Settings) {
    val ctx = LocalContext.current
    val pkg = remember(v) { Apps.resolve(ctx, key, known) }
    val saved = remember(v) { Prefs.str(ctx, key) != null }
    RowCard(title, if (pkg == null) "Не выбрано" else Apps.label(ctx, pkg) + if (saved) "" else " · авто", info = info, onClick = {
        s.pick(title) { p -> Prefs.put(ctx, key, p); s.settingsVersion++; s.overlay = back }
    }) {
        val ic = remember(pkg) { Apps.icon(ctx, pkg) }
        if (ic != null) Image(ic, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), filterQuality = FilterQuality.High)
        Spacer(Modifier.width(8.dp)); Chevron()
    }
}

@Composable
internal fun SwitchRow(title: String, value: Boolean, info: String? = null, onChange: (Boolean) -> Unit) {
    RowCard(title, onClick = { onChange(!value) }, info = info) {
        Box(
            Modifier.width(58.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (value) Color(0xFF3A7D44) else Color(0xFF2A2D30)),
            contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart
        ) { Box(Modifier.padding(4.dp).size(24.dp).clip(CircleShape).background(Color.White)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipsRow(title: String, options: List<Pair<String, String>>, selected: String, info: String? = null, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = t(18f, C.Text, FontWeight.Medium))
            if (info != null) InfoDot(open) { open = !open }
        }
        if (open && info != null) InfoText(info)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (id, label) ->
                val sel = id == selected
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) C.YellowBg else Color(0xFF181B1D))
                        .border(1.5.dp, if (sel) C.YellowBorder else C.Stroke, RoundedCornerShape(12.dp))
                        .clickable { onSelect(id) }.padding(horizontal = 16.dp, vertical = 10.dp)
                ) { Text(label, style = t(15f, if (sel) C.Text else C.Text2)) }
            }
        }
    }
}

// ============================ SEARCH ============================

/**
 * Поиск на карте: список слева (или справа — со стороны колонки блоков), живая карта остаётся на месте.
 * По мере набора результаты показываются и в списке, и маркерами на карте. Нажатие — маршрут.
 * Карта под панелью живёт (см. MapCard: covered исключает поиск); по ней можно нажать — построить маршрут в точку.
 */
@Composable
private fun SearchPanel(s: LauncherState, setHome: Boolean) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun hideKeyboard() { keyboard?.hide(); focusManager.clearFocus() }

    // карта остаётся на месте: открываем на главном экране, снимаем парковку, не «догоняем» машину
    LaunchedEffect(Unit) {
        if (s.page != Page.HOME) s.go(Page.HOME)
        s.parked = false; s.parkDismissed = true
        runCatching { focus.requestFocus() }
    }
    // нажали на карту (под панелью) — прячем клавиатуру, чтобы было видно карту
    LaunchedEffect(s.nav.tapTarget) { if (s.nav.tapTarget != null) hideKeyboard() }
    DisposableEffect(Unit) { onDispose { s.nav.clearPins() } }

    // поиск по мере набора (с задержкой) → список + маркеры на карте
    LaunchedEffect(query) {
        if (query.trim().length < 3) { results = emptyList(); s.nav.pins = emptyList(); return@LaunchedEffect }
        delay(500)
        loading = true
        s.nav.provider.search(query.trim(), s.vehicle.location,
            { results = it; s.nav.pins = it; loading = false; error = null },
            { error = it; loading = false })
    }
    // сразу построить маршрут (быстрые пункты: Домой/избранное) или сохранить дом
    fun routePick(p: Place, asHome: Boolean) {
        if (asHome) { s.nav.saveHome(p); Apps.toast(ctx, "Дом сохранён") }
        s.nav.rememberRecent(p); s.nav.clearPins(); s.overlay = null
        s.nav.routeTo(p, s.vehicle.location)
    }
    // выбрали адрес/место из поиска — открыть слева карточку с информацией (как в Яндекс.Картах)
    fun openCard(p: Place) {
        s.nav.rememberRecent(p)
        s.nav.pins = listOf(p); s.nav.selectedPin = p
        s.nav.showPlace(p, s.vehicle.location)   // тянет время в пути и организации; карточка слева
        s.overlay = null
    }

    // панель со стороны колонки блоков (карта по умолчанию справа → список слева)
    val onLeft = remember(s.settingsVersion) { HomeLayout.sideLeft(ctx) }
    val panel: @Composable () -> Unit = {
        Column(
            Modifier.width(600.dp).fillMaxHeight()
                .background(Brush.horizontalGradient(if (onLeft) listOf(Color(0xF2090B0C), Color(0xE6090B0C)) else listOf(Color(0xE6090B0C), Color(0xF2090B0C))))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(horizontal = 28.dp, vertical = 24.dp)
        ) {
            OverlayHeader(if (setHome) "Адрес дома" else "Куда едем?") { s.overlay = null }
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.TextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().height(72.dp).focusRequester(focus).clip(CardShape).border(Hairline, C.Stroke, CardShape),
                placeholder = { Text("Адрес или место", style = t(20f, C.Muted)) },
                textStyle = t(22f, C.Text), singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = C.Muted, modifier = Modifier.size(28.dp)) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) Box(Modifier.size(48.dp).clip(CircleShape).clickable { query = "" }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Close, "Очистить", tint = C.Muted, modifier = Modifier.size(24.dp)) }
                        Box(Modifier.size(48.dp).clip(CircleShape).clickable { hideKeyboard() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.KeyboardHide, "Скрыть клавиатуру", tint = C.Muted, modifier = Modifier.size(26.dp)) }
                    }
                },
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = C.Card, unfocusedContainerColor = C.Card, cursorColor = C.Yellow,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { hideKeyboard() }),
            )
            if (loading) Text("Ищу…", style = t(16f, C.Muted), modifier = Modifier.padding(top = 10.dp, start = 6.dp))
            error?.let { Text(it, style = t(16f, C.GuideRed), modifier = Modifier.padding(top = 10.dp, start = 6.dp)) }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                if (query.isBlank()) {
                    val home = s.nav.home
                    if (!setHome && home != null) item {
                        PlaceRow(Place("Домой", home.name, home.lat, home.lon), s, isHomeRow = true, onPick = { routePick(home, false) }, onHome = null)
                    }
                    if (!setHome && s.nav.favorites.isNotEmpty()) {
                        item { Section("Избранное") }
                        items(s.nav.favorites.size) { i -> val f = s.nav.favorites[i]
                            PlaceRow(f, s, isHomeRow = false, onPick = { routePick(f, false) }, onHome = null) }
                    }
                    if (s.nav.recents.isNotEmpty()) {
                        item { Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Section("История"); Spacer(Modifier.weight(1f))
                            Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { s.nav.clearRecents() }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text("Очистить", style = t(14f, C.Muted)) } } }
                        items(s.nav.recents.size) { i -> val r = s.nav.recents[i]
                            PlaceRow(r, s, isHomeRow = false, onPick = { if (setHome) routePick(r, true) else openCard(r) }, onHome = if (setHome) null else ({ routePick(r, true) })) }
                    }
                    if (home == null && s.nav.favorites.isEmpty() && s.nav.recents.isEmpty())
                        item { Text("Начните вводить адрес или место — результаты появятся здесь и на карте. Можно нажать точку прямо на карте.",
                            style = t(16f, C.Muted), modifier = Modifier.padding(6.dp)) }
                } else {
                    items(results.size) { i -> val p = results[i]
                        PlaceRow(p, s, isHomeRow = false, onPick = { if (setHome) routePick(p, true) else openCard(p) }, onHome = if (setHome) null else ({ routePick(p, true) })) }
                }
            }
        }
    }
    // панель прижата к своей стороне; остальное прозрачно — под ним живая карта (нажатие по ней строит маршрут)
    Row(Modifier.fillMaxSize()) {
        if (onLeft) { panel(); Spacer(Modifier.weight(1f)) } else { Spacer(Modifier.weight(1f)); panel() }
    }
}

@Composable
private fun PlaceRow(p: Place, s: LauncherState, isHomeRow: Boolean, onPick: () -> Unit, onHome: (() -> Unit)?, showStar: Boolean = !isHomeRow) {
    val dist = s.vehicle.location?.let {
        val r = FloatArray(1); android.location.Location.distanceBetween(it.latitude, it.longitude, p.lat, p.lon, r)
        if (r[0] >= 1000) "%.1f км".format(r[0] / 1000) else "${r[0].toInt()} м"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape)
            .clickable(onClick = onPick).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (isHomeRow) androidx.compose.material.icons.Icons.Outlined.Home else androidx.compose.material.icons.Icons.Outlined.Place,
            null, tint = if (isHomeRow) Color(0xFF6FA8FF) else C.Muted, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = t(18f, C.Text, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.description.isNotBlank()) Text(p.description, style = t(14f, C.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (dist != null) Text(dist, style = t(15f, C.Text2), modifier = Modifier.padding(horizontal = 10.dp))
        if (showStar) {
            val fav = s.nav.isFavorite(p)
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { s.nav.toggleFavorite(p) }, contentAlignment = Alignment.Center) {
                Icon(if (fav) Icons.Filled.Star else androidx.compose.material.icons.Icons.Outlined.StarOutline, "В избранное",
                    tint = if (fav) C.Yellow else C.Text2, modifier = Modifier.size(24.dp))
            }
        }
        if (onHome != null) Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onHome), contentAlignment = Alignment.Center) {
            Icon(androidx.compose.material.icons.Icons.Outlined.Home, "Сделать домом", tint = C.Text2, modifier = Modifier.size(24.dp))
        }
    }
}

// ============================ YANDEX KEY ============================

@Composable
private fun ApiKeyScreen(s: LauncherState) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Prefs.str(ctx, Prefs.YANDEX_KEY) ?: "") }
    val clip = ctx.getSystemService(ClipboardManager::class.java)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        OverlayHeader("Яндекс Карты") { s.overlay = Overlay.SettingsCat("map") }
        Text("Каждому нужен свой бесплатный ключ MapKit:\n1. На телефоне или компьютере откройте developer.tech.yandex.ru\n" +
            "2. «Подключить API» → «MapKit Mobile SDK» → скопируйте ключ\n3. Вставьте его сюда (или наберите) и нажмите «Сохранить».\n" +
            "Без ключа работает OpenStreetMap.", style = t(16f, C.Text2), modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 16.dp))
        androidx.compose.material3.TextField(
            value = key, onValueChange = { key = it.trim() },
            modifier = Modifier.fillMaxWidth().height(72.dp).clip(CardShape).border(Hairline, C.Stroke, CardShape),
            placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx", style = t(20f, C.Muted)) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = C.Text),
            singleLine = true,
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = C.Card, unfocusedContainerColor = C.Card, cursorColor = C.Yellow,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Вставить из буфера") { clip.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()?.let { key = it } }
            Pill("Удалить ключ") { key = "" }
        }
        Spacer(Modifier.height(16.dp))
        val valid = key.isEmpty() || Regex("^[0-9a-fA-F-]{30,40}$").matches(key)
        if (!valid) Text("Ключ выглядит неверно — обычно это 36 символов вида 1a2b3c4d-…", style = t(15f, C.GuideRed), modifier = Modifier.padding(6.dp))
        Box(Modifier.fillMaxWidth().height(64.dp).clip(CardShape).background(C.YellowBg).border(1.5.dp, C.YellowBorder, CardShape)
            .clickable(enabled = valid) {
                Prefs.putNow(ctx, Prefs.YANDEX_KEY, key.ifEmpty { null })
                Apps.toast(ctx, "Перезапускаю лаунчер…")
                YandexMaps.restartApp(ctx)
            }, contentAlignment = Alignment.Center) {
            Text("Сохранить и перезапустить", style = t(18f, C.Text, FontWeight.Medium))
        }
        YandexMaps.error?.takeIf { key.isNotEmpty() }?.let { Text("Состояние: $it", style = t(14f, C.Muted), modifier = Modifier.padding(6.dp)) }
    }
}

@Composable
private fun WeatherKeyScreen(s: LauncherState) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Prefs.str(ctx, Prefs.YANDEX_WEATHER_KEY) ?: "") }
    val clip = ctx.getSystemService(ClipboardManager::class.java)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        OverlayHeader("Яндекс Погода") { s.overlay = Overlay.SettingsCat("map") }
        Text("Погода из белого списка РФ — работает даже при ограничениях интернета. Нужен бесплатный ключ:\n" +
            "1. На телефоне/компьютере откройте yandex.ru/dev/weather\n2. Подключите тариф «Погода на вашем сайте» (бесплатный)\n" +
            "3. Скопируйте ключ и вставьте сюда.\nБез ключа погода берётся из запасных источников (могут не работать при ограничениях).",
            style = t(16f, C.Text2), modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 16.dp))
        androidx.compose.material3.TextField(
            value = key, onValueChange = { key = it.trim() },
            modifier = Modifier.fillMaxWidth().height(72.dp).clip(CardShape).border(Hairline, C.Stroke, CardShape),
            placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx", style = t(20f, C.Muted)) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = C.Text),
            singleLine = true,
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = C.Card, unfocusedContainerColor = C.Card, cursorColor = C.Yellow,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Вставить из буфера") { clip.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()?.let { key = it } }
            Pill("Удалить ключ") { key = "" }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(64.dp).clip(CardShape).background(C.YellowBg).border(1.5.dp, C.YellowBorder, CardShape)
            .clickable {
                Prefs.put(ctx, Prefs.YANDEX_WEATHER_KEY, key.ifEmpty { null })
                Wx.yandexWeatherKey = key.ifEmpty { null }
                s.settingsVersion++; Apps.toast(ctx, "Сохранено"); s.overlay = Overlay.SettingsCat("map")
            }, contentAlignment = Alignment.Center) {
            Text("Сохранить", style = t(18f, C.Text, FontWeight.Medium))
        }
    }
}

@Composable
private fun ProxyKeyScreen(s: LauncherState) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(Prefs.str(ctx, Prefs.PROXY_URL) ?: "") }
    val clip = ctx.getSystemService(ClipboardManager::class.java)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        OverlayHeader("Прокси (Yandex Cloud)") { s.overlay = Overlay.SettingsCat("map") }
        Text("Адрес вашей функции на Yandex Cloud (*.yandexcloud.net — белый список РФ). " +
            "Через него погода и ограничения скорости работают даже при ограничениях интернета. " +
            "Как создать — см. tools/proxy/README.md в проекте.\n\nВставьте адрес вида https://functions.yandexcloud.net/xxxxx",
            style = t(16f, C.Text2), modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 16.dp))
        androidx.compose.material3.TextField(
            value = url, onValueChange = { url = it.trim() },
            modifier = Modifier.fillMaxWidth().height(72.dp).clip(CardShape).border(Hairline, C.Stroke, CardShape),
            placeholder = { Text("https://functions.yandexcloud.net/...", style = t(18f, C.Muted)) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp, color = C.Text),
            singleLine = true,
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = C.Card, unfocusedContainerColor = C.Card, cursorColor = C.Yellow,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Вставить из буфера") { clip.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()?.let { url = it } }
            Pill("Удалить") { url = "" }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(64.dp).clip(CardShape).background(C.YellowBg).border(1.5.dp, C.YellowBorder, CardShape)
            .clickable {
                Prefs.put(ctx, Prefs.PROXY_URL, url.ifEmpty { null }); Wx.proxy = url.ifEmpty { null }
                s.settingsVersion++; Apps.toast(ctx, "Сохранено"); s.overlay = Overlay.SettingsCat("map")
            }, contentAlignment = Alignment.Center) { Text("Сохранить", style = t(18f, C.Text, FontWeight.Medium)) }
    }
}

// ============================ ACCENT ============================

@Composable
internal fun AccentRow(ctx: android.content.Context) {
    Column(Modifier.fillMaxWidth().clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text("Цвет акцента", style = t(18f, C.Text, FontWeight.Medium))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Accent.options.forEach { (argb, name) ->
                val col = Color(argb.toInt())
                val sel = Accent.color == col
                Box(Modifier.size(44.dp).clip(CircleShape).background(col)
                    .border(if (sel) 3.dp else Hairline, if (sel) Color.White else C.Stroke, CircleShape)
                    .clickable { Accent.set(ctx, argb) })
            }
        }
    }
}

// ============================ FAVORITES ============================

@Composable
private fun FavoritesScreen(s: LauncherState) {
    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Избранные места", action = { Pill("Добавить") { s.overlay = Overlay.Search() } }) { s.overlay = null }
        Text("Нажмите — маршрут. Добавить: найдите место в поиске и нажмите ☆.", style = t(15f, C.Muted), modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 12.dp))
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val home = s.nav.home
            if (home != null) item {
                PlaceRow(Place("Домой", home.name, home.lat, home.lon), s, isHomeRow = true, onPick = { s.overlay = null; s.nav.routeTo(home, s.vehicle.location) }, onHome = null)
            }
            items(s.nav.favorites.size) { i ->
                val f = s.nav.favorites[i]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PlaceRow(f, s, isHomeRow = false, onPick = { s.overlay = null; s.nav.routeTo(f, s.vehicle.location) }, onHome = null, showStar = false) }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(68.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).clickable { s.nav.removeFavorite(f) },
                        contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Delete, "Удалить", tint = C.Text2, modifier = Modifier.size(24.dp)) }
                }
            }
        }
    }
}

// ============================ WELCOME ============================

/** First run: name → home address → permissions. Everything optional, nothing blocks the launcher. */
@Composable
private fun WelcomeScreen(s: LauncherState) {
    val ctx = LocalContext.current
    // Step survives leaving to Android settings (the launcher may be recreated meanwhile) — kept in prefs.
    var step by remember { mutableIntStateOf(Prefs.str(ctx, "welcome_step", "0").toIntOrNull() ?: 0) }
    LaunchedEffect(step) { Prefs.put(ctx, "welcome_step", step.toString()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    fun finish() { Prefs.put(ctx, Prefs.ONBOARDED, true); s.settingsVersion++; s.overlay = null }
    fun next() {
        if (step < 2) step++ else finish()
    }
    LaunchedEffect(query) {
        if (query.trim().length < 3) { results = emptyList(); return@LaunchedEffect }
        delay(600); loading = true
        s.nav.provider.search(query.trim(), s.vehicle.location, { results = it; loading = false }, { loading = false })
    }
    val titles = listOf("Оформление", "Где ваш дом?", "Разрешения")
    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Первый запуск") { finish() }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 14.dp)) {
            repeat(3) { i ->
                Box(Modifier.width(if (i == step) 36.dp else 12.dp).height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (i <= step) C.Yellow else C.Stroke))
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Шаг ${step + 1} из 3 · ${titles[step]}", style = t(18f, C.Text, FontWeight.Medium))
        }
        Column(Modifier.weight(1f)) {
            when (step) {
                0 -> {
                    Text("Выберите цвет акцента — им подсвечиваются кнопки и активные элементы. Всё остальное можно поменять потом в настройках.",
                        style = t(17f, C.Text2), modifier = Modifier.padding(start = 6.dp, bottom = 14.dp))
                    AccentRow(ctx)
                }
                1 -> {
                    s.nav.home?.let { h -> Text("Сейчас: ${h.name}", style = t(16f, C.Text2), modifier = Modifier.padding(6.dp)) }
                    androidx.compose.material3.TextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().height(72.dp).clip(CardShape).border(Hairline, C.Stroke, CardShape),
                        placeholder = { Text("Адрес дома — для кнопки «Домой» на карте", style = t(20f, C.Muted)) },
                        textStyle = t(22f, C.Text), singleLine = true,
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = C.Card, unfocusedContainerColor = C.Card, cursorColor = C.Yellow,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    )
                    if (loading) Text("Ищу…", style = t(16f, C.Muted), modifier = Modifier.padding(6.dp))
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(results.size) { i ->
                            val p = results[i]
                            PlaceRow(p, s, isHomeRow = false, onPick = { s.nav.saveHome(p); Apps.toast(ctx, "Дом сохранён"); step = 2 }, onHome = null, showStar = false)
                        }
                    }
                }
                else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val steps = remember(s.settingsVersion) { Permissions.steps(ctx) }
                    steps.forEachIndexed { i, st ->
                        RowCard("${i + 1}. ${st.title}", st.why, onClick = { st.open(ctx) }) {
                            Text(if (st.done) "Готово" else "Включить", style = t(15f, if (st.done) C.GuideGreen else C.Yellow, FontWeight.Medium))
                            Spacer(Modifier.width(10.dp)); StatusDot(st.done); Spacer(Modifier.width(6.dp)); Chevron()
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) Box(Modifier.weight(1f).height(64.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape)
                .clickable { step-- }, contentAlignment = Alignment.Center) { Text("Назад", style = t(18f, C.Text2, FontWeight.Medium)) }
            if (step == 1) Box(Modifier.weight(1f).height(64.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape)
                .clickable { step = 2 }, contentAlignment = Alignment.Center) { Text("Пропустить", style = t(18f, C.Text2, FontWeight.Medium)) }
            Box(Modifier.weight(2f).height(64.dp).clip(CardShape).background(C.YellowBg).border(1.5.dp, C.YellowBorder, CardShape)
                .clickable { next() }, contentAlignment = Alignment.Center) {
                Text(if (step == 2) "Готово" else "Далее", style = t(18f, C.Text, FontWeight.Medium))
            }
        }
    }
}
