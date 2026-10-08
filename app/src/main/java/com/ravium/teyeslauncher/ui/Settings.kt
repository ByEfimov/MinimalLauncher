package com.ravium.teyeslauncher.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravium.teyeslauncher.*
import kotlinx.coroutines.delay

/** Settings: first a grid of categories, tap → the settings of that category. Every setting has an «i» with an explanation. */
private data class Category(val id: String, val title: String, val icon: ImageVector, val color: Color, val summary: (LauncherState) -> String)

private val categories = listOf(
    Category("base", "Основное", Icons.Outlined.Tune, Color(0xFF8E9AAF)) { s ->
        val m = Permissions.missing(s.activity).size
        (if (m == 0) "Всё настроено" else "Не выполнено пунктов: $m") + " · v${BuildConfig.VERSION_NAME}"
    },
    Category("look", "Экран", Icons.Outlined.Palette, Color(0xFFFFB340)) { s ->
        "Ночь: " + when (Prefs.str(s.activity, Prefs.NIGHT, "auto")) { "on" -> "всегда"; "off" -> "выкл"; else -> "авто" } + " · панель, плитки"
    },
    Category("apps", "Музыка и приложения", Icons.Outlined.LibraryMusic, Color(0xFFFF5E7E)) { s ->
        s.media.mainLabel + " · " + Apps.label(s.activity, Apps.resolve(s.activity, Prefs.NAV, Known.NAV))
    },
    Category("map", "Карта и маршруты", Icons.Outlined.Map, Color(0xFF4CD964)) { s ->
        val p = Prefs.str(s.activity, Prefs.MAP_PROVIDER, if (YandexMaps.enabled) "yandex" else "osm")
        val name = when { p == "2gis" -> "2ГИС"; p == "yandex" && YandexMaps.enabled -> "Яндекс"; p == "yandex" -> "Яндекс (нужен ключ)"; else -> "OpenStreetMap" }
        name + " · дом " + (if (s.nav.home != null) "задан" else "не задан")
    },
    Category("speed", "Скорость и камеры", Icons.Outlined.Speed, Color(0xFFFF6B5A)) { s ->
        if (Prefs.bool(s.activity, Prefs.LIMITS, true)) "Ограничения вкл · +" + Prefs.str(s.activity, Prefs.LIMIT_TOLERANCE, "10") else "Ограничения выкл"
    },
    Category("voice", "Голосовые подсказки", Icons.Outlined.RecordVoiceOver, Color(0xFF5AC8FA)) { s ->
        when (Voice.level(s.activity)) { "all" -> "Все: повороты и ограничения"; "off" -> "Выключены"; else -> "Важные: ограничения и камеры" }
    },
    Category("wheel", "Кнопки руля", Icons.Outlined.TouchApp, Color(0xFFAF7BFF)) { s ->
        if (WheelKeys.enabled(s.activity)) "Жесты включены" else "Долгое и двойное нажатие"
    },
    Category("car", "Машина и система", Icons.Outlined.DirectionsCar, Color(0xFF64D2FF)) { s ->
        "Пробег " + Reminders.fmtKm(s.vehicle.odometerKm) + " · память " + (Optimizer.mem(s.activity).usedFraction * 100).toInt() + "%"
    },
)

@Composable
fun SettingsHome(s: LauncherState) {
    val v = s.settingsVersion
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OverlayHeader("Настройки") { s.overlay = null }
            Spacer(Modifier.height(18.dp))
            val rows = categories.chunked(4)
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { c ->
                            val summary = remember(v) { runCatching { c.summary(s) }.getOrDefault("") }
                            Column(Modifier.weight(1f).fillMaxHeight().glass()
                                .clickable { s.overlay = if (c.id == "wheel") Overlay.Wheel else Overlay.SettingsCat(c.id) }
                                .padding(22.dp)) {
                                Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(c.color.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                                    Icon(c.icon, null, tint = c.color, modifier = Modifier.size(32.dp))
                                }
                                Spacer(Modifier.weight(1f))
                                Text(c.title, style = t(21f, C.Text, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(summary, style = t(14f, C.Muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            UpdateRow(s)
        }
    }
}

@Composable
private fun CategoryHeader(s: LauncherState, title: String) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(RoundedCornerShape(28.dp)).clickable { s.overlay = Overlay.Settings }.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(C.Card).border(Hairline, C.Stroke, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад", tint = C.Text, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text("Настройки", style = t(18f, C.Muted))
        }
        Text("›", style = t(26f, C.Muted))
        Spacer(Modifier.width(14.dp))
        Text(title, style = t(30f, C.Text, FontWeight.Medium))
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(56.dp).clip(CircleShape).background(C.Card).border(Hairline, C.Stroke, CircleShape).clickable { s.overlay = null },
            contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Close, null, tint = C.Text, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
fun SettingsCategory(s: LauncherState, id: String) {
    val ctx = LocalContext.current
    val v = s.settingsVersion
    val cat = categories.firstOrNull { it.id == id } ?: return
    val back = Overlay.SettingsCat(id)
    fun changed() { s.settingsVersion++ }
    @Composable fun sw(title: String, key: String, def: Boolean, info: String, after: () -> Unit = {}) =
        SwitchRow(title, remember(v) { Prefs.bool(ctx, key, def) }, info) { Prefs.put(ctx, key, it); changed(); after() }

    val left: @Composable ColumnScope.() -> Unit
    val right: @Composable ColumnScope.() -> Unit
    when (id) {
        "base" -> {
            left = {
                val missing = remember(v) { Permissions.missing(ctx).size }
                RowCard("Настройка магнитолы", if (missing == 0) "Все разрешения выданы" else "Не выполнено пунктов: $missing",
                    info = "Разрешения Android, без которых лаунчер работает не полностью: доступ к уведомлениям (музыка и подсказки навигатора), " +
                        "геопозиция (карта и скорость), поверх других окон и история использования (автозапуск и возврат из штатного лаунчера).",
                    onClick = { s.overlay = Overlay.Setup }) { StatusDot(missing == 0); Spacer(Modifier.width(10.dp)); Chevron() }
                sw("Не выпускать в штатный лаунчер", Prefs.KIOSK, true,
                    "Если открылся штатный рабочий стол TEYES, Minimal Drive сам вернётся через полсекунды. Выключите, когда нужно зайти в штатное меню магнитолы.")
            }
            right = {
                UpdateRow(s, "Новые версии приходят с GitHub. Лаунчер проверяет их сам при запуске и раз в 6 часов; установка — одним нажатием, настройки сохраняются.")
                RowCard("Диагностика", "Плееры, GPS, разрешения, журнал ошибок",
                    info = "Техническая информация о магнитоле и лаунчере. Кнопка «Отправить отчёт» пришлёт разработчику всё нужное, если что-то не работает.",
                    onClick = { s.overlay = Overlay.Diagnostics }) { Chevron() }
                RowCard("Системные настройки Android", info = "Обычные настройки Android: Wi-Fi, Bluetooth, дата и время, приложения.",
                    onClick = { Apps.openFirst(ctx, Intent(Settings.ACTION_SETTINGS)) }) { Chevron() }
                var confirmDel by remember { mutableStateOf(false) }
                RowCard(if (confirmDel) "Точно удалить? Нажмите ещё раз" else "Удалить лаунчер",
                    "Вернуть штатный лаунчер и удалить Minimal Drive",
                    info = "Отключит режим киоска и автозапуск, откроет выбор домашнего приложения, чтобы вернуть штатный лаунчер, и запустит удаление Minimal Drive. " +
                        "Android попросит подтвердить удаление. Ваши настройки лаунчера при этом стираются вместе с приложением; сама магнитола не трогается.",
                    onClick = { if (confirmDel) Kiosk.uninstallAndRevert(ctx) else confirmDel = true }) {
                    Text(if (confirmDel) "Удалить" else "", style = t(15f, C.GuideRed, FontWeight.Medium)); Chevron()
                }
            }
        }
        "look" -> {
            left = {
                AccentRow(ctx)
                val night = remember(v) { Prefs.str(ctx, Prefs.NIGHT, "auto") }
                ChipsRow("Ночной режим", listOf("auto" to "Авто (закат)", "on" to "Всегда", "off" to "Выкл"), night,
                    "Затемняет экран, чтобы не слепил ночью. «Авто» — включается после заката и выключается на рассвете по вашим GPS-координатам.") {
                    Prefs.put(ctx, Prefs.NIGHT, it); changed(); s.updateNight()
                }
                val dim = remember(v) { Prefs.str(ctx, Prefs.NIGHT_DIM, "0.3") }
                ChipsRow("Затемнение ночью", listOf("0.15" to "Слабое", "0.3" to "Среднее", "0.45" to "Сильное"), dim,
                    "Насколько темнее становится экран в ночном режиме. Штатная яркость магнитолы при этом не меняется.") { Prefs.put(ctx, Prefs.NIGHT_DIM, it); changed() }
            }
            right = {
                RowCard("Главный экран", "Карта или часы, музыка, блоки и их порядок", info = "Что показывать на главном экране: карту или крупные часы, " +
                    "с какой стороны колонка, и какие блоки в ней — музыка, панель приложений, погода, скорость, громкость, напоминание и другие.",
                    onClick = { s.overlay = Overlay.HomeLayout }) { Chevron() }
                RowCard("Нижняя панель", "Кнопки и приложения внизу экрана", info = "Выберите, какие кнопки показывать внизу, в каком порядке, и добавьте любые приложения. " +
                    "Долгое нажатие на кнопку внизу тоже открывает эту настройку.", onClick = { s.overlay = Overlay.Dock }) { Chevron() }
                RowCard("Плитки", "Экран плиток: размер, порядок, новые", info = "Экран с плитками в стиле пункта управления iPhone. Открывается свайпом по приветствию. " +
                    "Удерживайте плитку, чтобы перетащить её или изменить размер за уголок.", onClick = { s.overlay = null; s.go(Page.TILES); s.tilesEdit = true }) { Chevron() }
                sw("Экран парковки", Prefs.PARKING, true,
                    "Когда машина стоит, вместо карты показываются крупные часы, погода и ближайшее напоминание. Как только поедете — карта вернётся сама. Нажатие на экран парковки тоже возвращает карту.")
                val pd = remember(v) { Prefs.str(ctx, Prefs.PARKING_DELAY, "2") }
                ChipsRow("Показывать, если стоим дольше", listOf("1" to "1 мин", "2" to "2 мин", "5" to "5 мин", "10" to "10 мин"), pd,
                    "Через сколько минут без движения включается экран парковки. На светофорах он не появится, если поставить 2 минуты и больше.") {
                    Prefs.put(ctx, Prefs.PARKING_DELAY, it); changed()
                }
            }
        }
        "apps" -> {
            left = {
                val mainPkg = s.media.mainPkg
                RowCard("Основной плеер", s.media.mainLabel + (if (Prefs.str(ctx, Prefs.MAIN_PLAYER) == null) " · авто" else ""),
                    info = "Приложение первой вкладки на карточке музыки: Яндекс Музыка, VK, Звук, Spotify или локальный плеер. " +
                        "Вторая вкладка — Bluetooth: туда попадает всё остальное, что играет (например, музыка с телефона).",
                    onClick = { s.pick("Основной плеер", onReset = { s.media.setMainPlayer(null); s.overlay = back }) { p ->
                        s.media.setMainPlayer(p); s.settingsVersion++; s.overlay = back } }) {
                    val ic = remember(mainPkg) { Apps.icon(ctx, mainPkg) }
                    if (ic != null) Image(ic, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), filterQuality = FilterQuality.High)
                    Spacer(Modifier.width(8.dp)); Chevron()
                }
                AppRow(s, v, "Навигатор", Prefs.NAV, Known.NAV, "Открывается кнопкой «развернуть» на карте и кнопкой навигации внизу. Его подсказки о поворотах показываются на главном экране.", back)
                AppRow(s, v, "Телефон", Prefs.PHONE, Known.PHONE, "Приложение звонков — обычно штатное Bluetooth-приложение TEYES.", back)
                AppRow(s, v, "CarPlay / CarLink", Prefs.CARLINK, Known.CARLINK, "Открывается плиткой CarPlay под музыкой.", back)
            }
            right = {
                sw("Bluetooth: открывать BT-экран магнитолы", Prefs.BT_OPEN_APP, false,
                    "На некоторых прошивках TEYES звук с телефона переключается на Bluetooth, только когда открыт штатный BT-экран. Включите, если на вкладке Bluetooth нет звука.")
                RowCard("Обход белых списков (VPN)",
                    if (Vpn.installed(ctx)) Apps.label(ctx, Vpn.app(ctx)) + " · кнопка на карте" else "Не настроен — нажмите",
                    info = "Открывает приложение-обход (Happ и др.) кнопкой на главном и показывает, включён ли VPN. " +
                        "Можно вставить ссылку-подписку и импортировать её в Happ. Сам туннель поднимает приложение-обход.",
                    onClick = { s.overlay = Overlay.Vpn }) {
                    val ic = remember(s.settingsVersion) { Apps.icon(ctx, Vpn.app(ctx)) }
                    if (ic != null) Image(ic, null, Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)), filterQuality = FilterQuality.High)
                    Spacer(Modifier.width(8.dp)); Chevron()
                }
                Section("При включении зажигания")
                sw("Продолжить музыку при включении", Prefs.AUTO_PLAY, false, "Через несколько секунд после включения магнитолы сам продолжает играть то, что играло перед выключением. По умолчанию выключено — музыка не включается сама.")
                sw("Открыть навигатор", Prefs.AUTO_NAV, false, "Сразу после включения открывает выбранный навигатор на весь экран.")
            }
        }
        "map" -> {
            left = {
                val provider = remember(v) { Prefs.str(ctx, Prefs.MAP_PROVIDER, if (YandexMaps.enabled) "yandex" else "osm") }
                ChipsRow("Карта на экране", listOf("osm" to "OpenStreetMap", "yandex" to "Яндекс", "2gis" to "2ГИС"), provider,
                    "Какую карту показывать на главном экране. OpenStreetMap и 2ГИС работают без ключа (их серверы в белом списке РФ). " +
                        "2ГИС — подробная российская карта с номерами домов и организациями, часто удобнее в городе. " +
                        "Яндекс даёт пробки и поиск Яндекса, но нужен бесплатный ключ MapKit. Маршруты, поиск и метки работают на любой карте.") { pick ->
                    Prefs.put(ctx, Prefs.MAP_PROVIDER, pick); changed()
                    if (pick == "yandex" && !YandexMaps.enabled) s.overlay = Overlay.ApiKey
                }
                RowCard("Ключ Яндекс Карт (пробки)",
                    if (YandexMaps.enabled) "Ключ введён · пробки и поиск Яндекса" else "Нажмите, чтобы ввести ключ MapKit",
                    info = "С бесплатным ключом Яндекс MapKit доступна карта «Яндекс» с пробками, маршрутами с учётом пробок и поиском Яндекса. " +
                        "Без ключа выбирайте OpenStreetMap или 2ГИС — они работают всегда.",
                    onClick = { s.overlay = Overlay.ApiKey }) { Chevron() }
                RowCard("Ключ 2ГИС (пробки и маршруты)",
                    if (Gis2.enabled(ctx)) "Ключ введён · родные пробки и маршруты 2ГИС" else "Нажмите, чтобы ввести ключ 2ГИС (MapGL)",
                    info = "С бесплатным ключом 2ГИС карта становится настоящей векторной: родные пробки и маршруты строит сам 2ГИС своей полоской. " +
                        "Без ключа 2ГИС показывается растровыми плитками — без пробок, маршрут рисуем мы.",
                    onClick = { s.overlay = Overlay.Gis2Key }) { Chevron() }
                val home = s.nav.home
                RowCard("Дом", home?.let { it.name + (if (it.description.isNotBlank()) ", " + it.description else "") } ?: "Не задан — нажмите, чтобы найти адрес",
                    info = "Кнопка «Домой» на карте строит маршрут сюда. Кнопки руля и плитка «Домой» тоже используют этот адрес.",
                    onClick = { s.overlay = Overlay.Search(setHome = true) }) { Chevron() }
                RowCard("Погода: " + (if (Prefs.str(ctx, Prefs.YANDEX_WEATHER_KEY).isNullOrBlank()) "запасные источники" else "Яндекс (белый список)"),
                    if (Prefs.str(ctx, Prefs.YANDEX_WEATHER_KEY).isNullOrBlank()) "Нажмите, чтобы ввести ключ Яндекс Погоды" else "Ключ введён · нажмите, чтобы изменить",
                    info = "Яндекс Погода на белом списке РФ — работает даже при ограничениях интернета. Нужен бесплатный ключ (yandex.ru/dev/weather). " +
                        "Без ключа погода берётся из wttr.in / met.no / Open-Meteo — они могут не работать при ограничениях.",
                    onClick = { s.overlay = Overlay.WeatherKey }) { Chevron() }
                RowCard("Прокси (Yandex Cloud)", if (Prefs.str(ctx, Prefs.PROXY_URL).isNullOrBlank()) "Не задан — для погоды и ограничений при блокировках" else "Задан · белый список РФ",
                    info = "Свой сервис на Yandex Cloud (*.yandexcloud.net — белый список РФ). Через него и погода, и ограничения скорости работают при ограничениях интернета. Настройка — tools/proxy/README.md.",
                    onClick = { s.overlay = Overlay.ProxyKey }) { Chevron() }
                RowCard("Избранные места", "${s.nav.favorites.size} мест", info = "Работа, дача, спортзал… Добавляются звёздочкой в поиске. Маршрут — одно нажатие.",
                    onClick = { s.overlay = Overlay.Favorites }) { Chevron() }
                RowCard("Карты без интернета",
                    if (!OfflineMaps.available) "Нужен ключ Яндекс Карт" else "Сохранено: " + (OfflineMaps.cacheSize ?: "…"),
                    info = "Заранее скачайте область или город — карта, маршруты и поиск будут работать без связи. " +
                        "На карте кнопка «Скачать карту района» показывается, только пока район ещё не сохранён; " +
                        "отсюда список доступен всегда. Нужен ключ Яндекс MapKit.",
                    onClick = { s.overlay = Overlay.Offline }) { Chevron() }
            }
            right = {
                val style = remember(v) { Prefs.str(ctx, Prefs.MAP_STYLE, "dark") }
                ChipsRow("Стиль карты", listOf("dark" to "Тёмная", "light" to "Светлая"), style, "Тёмная меньше слепит и лучше подходит к дизайну; светлая лучше читается на солнце.") {
                    Prefs.put(ctx, Prefs.MAP_STYLE, it); changed()
                }
                sw("Поворачивать по направлению движения", Prefs.MAP_HEADING, true, "Карта поворачивается так, что дорога впереди всегда сверху, как в навигаторе. Если выключить — север всегда сверху.")
                if (YandexMaps.enabled) sw("Пробки", Prefs.MAP_TRAFFIC, true, "Цветные линии пробок на карте Яндекса.")
                sw("Лёгкий режим карты", Prefs.LITE_MAP, false, "Без 3D-наклона и пробок, карта сдвигается реже. Помогает, если магнитола подтормаживает или карта дёргается.")
            }
        }
        "speed" -> {
            left = {
                sw("Ограничения скорости", Prefs.LIMITS, true, "Знак ограничения в шапке берётся из OpenStreetMap для дороги, по которой вы едете. " +
                    "Если знака нет в базе — используются правила ПДД: 60 в городе, 90 за городом, 20 в жилой зоне.")
                val tol = remember(v) { Prefs.str(ctx, Prefs.LIMIT_TOLERANCE, "10") }
                ChipsRow("Превышение считается от", listOf("0" to "0 км/ч", "10" to "+10", "19" to "+19", "20" to "+20"), tol,
                    "Скорость становится красной и звучит предупреждение, только если вы едете быстрее ограничения на это значение. +19 — порог штрафа в России.") {
                    Prefs.put(ctx, Prefs.LIMIT_TOLERANCE, it); changed()
                }
            }
            right = {
                sw("Предупреждать о превышении", Prefs.SOUND_OVERSPEED, true, "Голос (или короткий сигнал, если голос выключен) при превышении, повтор не чаще раза в 30 секунд.")
                sw("Предупреждать о камерах", Prefs.CAMERA_WARN, true, "Предупреждение примерно за 400 м до камеры контроля скорости из базы OpenStreetMap.")
            }
        }
        "voice" -> {
            left = {
                val lvl = remember(v) { Voice.level(ctx) }
                ChipsRow("Подсказки", listOf("all" to "Все", "important" to "Важные", "off" to "Нет"), lvl,
                    "Все — повороты по маршруту («Через 300 метров поверните направо»), новые ограничения, камеры и превышение.\n" +
                        "Важные — только ограничения, камеры и превышение.\nНет — лаунчер молчит (остаются короткие сигналы, если они включены в «Скорость и камеры»).") {
                    Prefs.put(ctx, Prefs.VOICE_LEVEL, it); changed()
                }
                val rate = remember(v) { Prefs.str(ctx, Prefs.VOICE_RATE, "1.0") }
                ChipsRow("Скорость речи", listOf("0.8" to "Медленно", "1.0" to "Обычно", "1.25" to "Быстро"), rate, "Как быстро говорит голос.") {
                    Prefs.put(ctx, Prefs.VOICE_RATE, it); Voice.apply(); changed()
                }
            }
            right = {
                var voices by remember { mutableStateOf(Voice.voices()) }
                LaunchedEffect(Unit) { repeat(10) { if (voices.isEmpty()) { delay(700); voices = Voice.voices() } } }
                val vname = remember(v) { Prefs.str(ctx, Prefs.VOICE_NAME) ?: "" }
                if (voices.size > 1) ChipsRow("Голос", listOf("" to "По умолчанию") + voices, vname,
                    "Голоса синтезатора речи, установленные на магнитоле. Нажмите — прозвучит пример. Больше голосов даёт «Синтезатор речи Google».") {
                    Prefs.put(ctx, Prefs.VOICE_NAME, it.ifEmpty { null }); Voice.apply(); changed(); Voice.test(ctx)
                }
                RowCard("Проверить голос", if (Voice.isReady) "Прозвучит пример подсказки" else "Синтез речи не найден — установите «Синтезатор речи Google»",
                    info = "Голос идёт через навигационный канал: музыка на время подсказки становится тише, а не останавливается.",
                    onClick = { Voice.test(ctx) }) { Chevron() }
            }
        }
        else -> {   // car
            left = {
                val next = remember(v) { Reminders.states(ctx, s.vehicle.odometerKm).firstOrNull() }
                RowCard("Напоминания", next?.let { "${it.r.title}: ${it.left}" } ?: "Масло, фильтры, ОСАГО, техосмотр",
                    info = "Напомнит о замене масла по пробегу и об окончании ОСАГО или техосмотра по дате. Экран напоминаний — свайпом по приветствию.",
                    onClick = { s.overlay = null; s.go(Page.REMINDERS) }) { Chevron() }
                RowCard("Пробег", Reminders.fmtKm(s.vehicle.odometerKm), info = "Пробег считается по GPS. Один раз введите значение с приборной панели — дальше лаунчер прибавляет сам.",
                    onClick = { s.overlay = Overlay.Odometer }) { Chevron() }
            }
            right = {
                val mem = remember(v) { Optimizer.mem(ctx) }
                RowCard("Оптимизация магнитолы", "Память занята на ${(mem.usedFraction * 100).toInt()}%",
                    info = "Очистка памяти, приложения, которые запускаются сами, и скорость анимаций. Помогает, если магнитола тормозит.",
                    onClick = { s.overlay = null; s.go(Page.OPTIMIZE) }) { StatusDot(mem.usedFraction < 0.85f, C.Yellow); Spacer(Modifier.width(10.dp)); Chevron() }
                sw("Автоочистка памяти", Prefs.AUTO_CLEAN, true,
                    "При включении зажигания, раз в 30 минут и когда памяти мало закрывает фоновые приложения. Плеер, навигатор, телефон и CarLink не трогаются.")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        CategoryHeader(s, cat.title)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = left)
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = right)
        }
    }
}
