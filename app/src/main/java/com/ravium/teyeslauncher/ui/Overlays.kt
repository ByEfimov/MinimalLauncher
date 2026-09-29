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
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
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

private fun t(size: Float, color: Color = C.Text, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, color = color, fontWeight = weight, lineHeight = (size * 1.25f).sp)

@Composable
fun BoxScope.Overlays(s: LauncherState) {
    val o = s.overlay
    AnimatedVisibility(o != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
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
                is Overlay.Settings -> SettingsScreen(s)
                is Overlay.Setup -> SetupScreen(s)
                is Overlay.Diagnostics -> DiagnosticsScreen(s)
                is Overlay.Search -> SearchScreen(s, o.setHome)
                is Overlay.ApiKey -> ApiKeyScreen(s)
                is Overlay.Name -> NameScreen(s)
                null -> {}
            }
        }
    }
}

@Composable
private fun OverlayHeader(title: String, action: (@Composable RowScope.() -> Unit)? = null, onClose: () -> Unit) {
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
private fun Pill(label: String, onClick: () -> Unit) {
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
private fun SettingsScreen(s: LauncherState) {
    val ctx = LocalContext.current
    val v = s.settingsVersion
    fun changed() { s.settingsVersion++ }
    fun sw(title: String, key: String, def: Boolean, after: () -> Unit = {}) =
        @Composable { SwitchRow(title, remember(v) { Prefs.bool(ctx, key, def) }) { Prefs.put(ctx, key, it); changed(); after() } }

    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Настройки") { s.overlay = null }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // ---- left column
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Section("Лаунчер")
                val missing = remember(v) { Permissions.missing(ctx).size }
                RowCard("Настройка магнитолы", if (missing == 0) "Все разрешения выданы" else "Не выполнено пунктов: $missing",
                    onClick = { s.overlay = Overlay.Setup }) { StatusDot(missing == 0); Spacer(Modifier.width(10.dp)); Chevron() }
                val uname = remember(v) { Prefs.str(ctx, Prefs.USER_NAME, "") }
                RowCard("Имя в приветствии", uname.ifBlank { "Не задано" }, onClick = { s.overlay = Overlay.Name }) { Chevron() }
                sw("Не выпускать в штатный лаунчер", Prefs.KIOSK, true)()
                RowCard("Диагностика", "Камеры, плееры, GPS, разрешения", onClick = { s.overlay = Overlay.Diagnostics }) { Chevron() }
                UpdateRow(s)
                Spacer(Modifier.height(8.dp))
                Section("Приложения")
                AppRow(s, v, "Навигатор (карты)", Prefs.NAV, Known.NAV)
                AppRow(s, v, "Телефон", Prefs.PHONE, Known.PHONE)
                AppRow(s, v, "CarPlay / CarLink", Prefs.CARLINK, Known.CARLINK)
                sw("Bluetooth: открывать BT-экран магнитолы", Prefs.BT_OPEN_APP, false)()
                Text("Иконки нижней панели: долгое нажатие на иконку → выбрать приложение.", style = t(14f, C.Muted), modifier = Modifier.padding(horizontal = 6.dp))
                Spacer(Modifier.height(8.dp))
                Section("При включении зажигания")
                sw("Продолжить музыку", Prefs.AUTO_PLAY, true)()
                sw("Открыть навигатор", Prefs.AUTO_NAV, false)()
                Spacer(Modifier.height(8.dp))
                Section("Экран")
                val night = remember(v) { Prefs.str(ctx, Prefs.NIGHT, "auto") }
                ChipsRow("Ночной режим", listOf("auto" to "Авто (закат)", "on" to "Всегда", "off" to "Выкл"), night) {
                    Prefs.put(ctx, Prefs.NIGHT, it); changed(); s.updateNight()
                }
                val dim = remember(v) { Prefs.str(ctx, Prefs.NIGHT_DIM, "0.3") }
                ChipsRow("Затемнение ночью", listOf("0.15" to "Слабое", "0.3" to "Среднее", "0.45" to "Сильное"), dim) { Prefs.put(ctx, Prefs.NIGHT_DIM, it); changed() }
                RowCard("Системные настройки Android", onClick = { Apps.openFirst(ctx, Intent(Settings.ACTION_SETTINGS)) }) { Chevron() }
            }
            // ---- right column
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Section("Карта")
                RowCard("Карта: " + (if (YandexMaps.enabled) "Яндекс" else "OpenStreetMap"),
                    if (YandexMaps.enabled) "Ключ Яндекса введён · нажмите, чтобы изменить" else "Нажмите, чтобы ввести ключ Яндекс Карт",
                    onClick = { s.overlay = Overlay.ApiKey }) { Chevron() }
                val home = s.nav.home
                RowCard("Дом", home?.let { it.name + (if (it.description.isNotBlank()) ", " + it.description else "") } ?: "Не задан — нажмите, чтобы найти адрес",
                    onClick = { s.overlay = Overlay.Search(setHome = true) }) { Chevron() }
                val style = remember(v) { Prefs.str(ctx, Prefs.MAP_STYLE, "dark") }
                ChipsRow("Стиль карты", listOf("dark" to "Тёмная", "light" to "Светлая"), style) { Prefs.put(ctx, Prefs.MAP_STYLE, it); changed() }
                sw("Поворачивать по направлению движения", Prefs.MAP_HEADING, true)()
                if (YandexMaps.enabled) sw("Пробки", Prefs.MAP_TRAFFIC, true)()
                Spacer(Modifier.height(8.dp))
                Section("Скорость")
                sw("Ограничения скорости (OpenStreetMap)", Prefs.LIMITS, true)()
                val tol = remember(v) { Prefs.str(ctx, Prefs.LIMIT_TOLERANCE, "10") }
                ChipsRow("Превышение считается от", listOf("0" to "0 км/ч", "10" to "+10", "19" to "+19", "20" to "+20"), tol) {
                    Prefs.put(ctx, Prefs.LIMIT_TOLERANCE, it); changed()
                }
                sw("Звук при превышении", Prefs.SOUND_OVERSPEED, true)()
                sw("Предупреждать о камерах", Prefs.CAMERA_WARN, true)()
            }
        }
    }
}

@Composable
private fun UpdateRow(s: LauncherState) {
    val u = s.updater
    val rel = u.available
    RowCard(
        if (rel != null) "Установить обновление ${rel.version}" else "Обновления",
        u.status.ifEmpty { "Версия ${BuildConfig.VERSION_NAME} · нажмите, чтобы проверить" },
        onClick = { if (rel != null) u.install() else u.check() }
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
        if (Prefs.str(ctx, Prefs.USER_NAME, "").isBlank())
            RowCard("Как вас зовут?", "Имя для приветствия на главном экране", onClick = { s.overlay = Overlay.Name }) { Chevron() }
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
            .clickable { s.overlay = null }, contentAlignment = Alignment.Center) {
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
            Pill("Скопировать") {
                val text = sections.joinToString("\n\n") { (h, l) -> "$h\n" + l.joinToString("\n") }
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("diag", text))
                Apps.toast(ctx, "Скопировано")
            }
        }) { s.overlay = Overlay.Settings }
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

// ============================ building blocks ============================

@Composable private fun Section(title: String) = Text(title.uppercase(), style = t(13f, C.Muted, FontWeight.Medium).copy(letterSpacing = 1.5.sp), modifier = Modifier.padding(start = 6.dp, top = 4.dp))

@Composable private fun StatusDot(ok: Boolean, bad: Color = C.GuideRed) = Box(Modifier.size(12.dp).clip(CircleShape).background(if (ok) C.GuideGreen else bad))

@Composable
private fun RowCard(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = t(18f, C.Text, FontWeight.Medium))
            if (subtitle != null) { Spacer(Modifier.height(3.dp)); Text(subtitle, style = t(14f, C.Muted), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        trailing()
    }
}

@Composable private fun Chevron() = Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = C.Muted, modifier = Modifier.size(28.dp))

@Composable
private fun AppRow(s: LauncherState, v: Int, title: String, key: String, known: List<String>) {
    val ctx = LocalContext.current
    val pkg = remember(v) { Apps.resolve(ctx, key, known) }
    val saved = remember(v) { Prefs.str(ctx, key) != null }
    RowCard(title, if (pkg == null) "Не выбрано" else Apps.label(ctx, pkg) + if (saved) "" else " · авто", onClick = {
        s.pick(title) { p -> Prefs.put(ctx, key, p); s.settingsVersion++; s.overlay = Overlay.Settings }
    }) {
        val ic = remember(pkg) { Apps.icon(ctx, pkg) }
        if (ic != null) Image(ic, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), filterQuality = FilterQuality.High)
        Spacer(Modifier.width(8.dp)); Chevron()
    }
}

@Composable
private fun SwitchRow(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    RowCard(title, onClick = { onChange(!value) }) {
        Box(
            Modifier.width(58.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (value) Color(0xFF3A7D44) else Color(0xFF2A2D30)),
            contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart
        ) { Box(Modifier.padding(4.dp).size(24.dp).clip(CircleShape).background(Color.White)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsRow(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(title, style = t(18f, C.Text, FontWeight.Medium))
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

/** Address / place search. Tap a result → route; the house button saves it as «Дом». */
@Composable
private fun SearchScreen(s: LauncherState, setHome: Boolean) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // search as you type (debounced)
    LaunchedEffect(query) {
        if (query.trim().length < 3) { results = emptyList(); return@LaunchedEffect }
        delay(600)
        loading = true
        s.nav.provider.search(query.trim(), s.vehicle.location, { results = it; loading = false; error = null }, { error = it; loading = false })
    }
    fun choose(p: Place, asHome: Boolean) {
        if (asHome) { s.nav.saveHome(p); Apps.toast(ctx, "Дом сохранён") }
        s.overlay = null
        s.nav.routeTo(p, s.vehicle.location)
    }
    Column(Modifier.fillMaxSize()) {
        OverlayHeader(if (setHome) "Адрес дома" else "Куда едем?") { s.overlay = null }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.TextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().height(72.dp).focusRequester(focus).clip(CardShape).border(Hairline, C.Stroke, CardShape),
            placeholder = { Text("Адрес или место", style = t(20f, C.Muted)) },
            textStyle = t(22f, C.Text),
            singleLine = true,
            leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Search, null, tint = C.Muted, modifier = Modifier.size(28.dp)) },
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = C.Card, unfocusedContainerColor = C.Card, cursorColor = C.Yellow,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        )
        Spacer(Modifier.height(10.dp))
        val home = s.nav.home
        if (!setHome && home != null && query.isBlank()) {
            PlaceRow(Place("Домой", home.name, home.lat, home.lon), s, isHomeRow = true, onPick = { choose(home, false) }, onHome = null)
        }
        if (loading) Text("Ищу…", style = t(16f, C.Muted), modifier = Modifier.padding(6.dp))
        error?.let { Text(it, style = t(16f, C.GuideRed), modifier = Modifier.padding(6.dp)) }
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results.size) { i ->
                val p = results[i]
                PlaceRow(p, s, isHomeRow = false, onPick = { choose(p, setHome) }, onHome = if (setHome) null else ({ choose(p, true) }))
            }
        }
    }
}

@Composable
private fun PlaceRow(p: Place, s: LauncherState, isHomeRow: Boolean, onPick: () -> Unit, onHome: (() -> Unit)?) {
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
        OverlayHeader("Яндекс Карты") { s.overlay = Overlay.Settings }
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

// ============================ NAME ============================

@Composable
private fun NameScreen(s: LauncherState) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Prefs.str(ctx, Prefs.USER_NAME, "")) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun save() { Prefs.put(ctx, Prefs.USER_NAME, name.trim()); s.settingsVersion++; s.overlay = null }
    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Как вас зовут?") { s.overlay = null }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.TextField(
            value = name, onValueChange = { name = it.take(24) },
            modifier = Modifier.fillMaxWidth().height(72.dp).focusRequester(focus).clip(CardShape).border(Hairline, C.Stroke, CardShape),
            placeholder = { Text("Имя", style = t(22f, C.Muted)) },
            textStyle = t(24f, C.Text), singleLine = true,
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = C.Card, unfocusedContainerColor = C.Card, cursorColor = C.Yellow,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
        )
        Spacer(Modifier.height(10.dp))
        Text("На главном экране: «Добро пожаловать, ${name.trim().ifEmpty { "…" }}»", style = t(16f, C.Muted), modifier = Modifier.padding(6.dp))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(64.dp).clip(CardShape).background(C.YellowBg).border(1.5.dp, C.YellowBorder, CardShape)
            .clickable { save() }, contentAlignment = Alignment.Center) { Text("Сохранить", style = t(18f, C.Text, FontWeight.Medium)) }
    }
}
