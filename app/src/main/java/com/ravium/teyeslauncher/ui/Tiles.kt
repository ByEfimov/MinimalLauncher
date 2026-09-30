package com.ravium.teyeslauncher.ui

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravium.teyeslauncher.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================ model ============================

data class TileSpec(val type: String, val w: Int, val h: Int, val arg: String? = null) {
    fun enc() = "$type:${w}x$h" + (arg?.let { ":$it" } ?: "")
    companion object {
        fun dec(s: String): TileSpec? = runCatching {
            val p = s.split(':')
            val (w, h) = p[1].split('x').map { it.toInt() }
            TileSpec(p[0], w, h, p.getOrNull(2))
        }.getOrNull()
    }
}

data class TileDef(val type: String, val title: String, val group: String, val desc: String, val sizes: List<Pair<Int, Int>>, val icon: ImageVector)

// 8-column grid (cells ≈ square). T = tall 1×2 slider like the iOS volume/brightness controls.
private val S = 1 to 1; private val M = 2 to 1; private val L = 2 to 2; private val T = 1 to 2; private val W = 4 to 1; private val XL = 4 to 2

object TileCatalog {
    // Only what you switch, use or glance at often while driving — the rest lives in Настройки.
    val groups = listOf("Полезное", "Переключатели", "Машина")
    val defs = listOf(
        TileDef("clock", "Часы", "Полезное", "Время и дата крупно", listOf(L, M, XL, S), Icons.Outlined.Schedule),
        TileDef("speed", "Спидометр", "Полезное", "Скорость и ограничение", listOf(L, M, S), Icons.Outlined.Speed),
        TileDef("weather", "Погода", "Полезное", "Сейчас; нажатие — прогноз", listOf(M, L, S), Icons.Outlined.WbSunny),
        TileDef("home", "Домой", "Полезное", "Маршрут домой одним нажатием", listOf(S, M), Icons.Outlined.Home),
        TileDef("places", "Избранные места", "Полезное", "Дом и избранное — маршрут в одно касание", listOf(M, W, L), Icons.Outlined.StarOutline),
        TileDef("search", "Куда едем", "Полезное", "Поиск адреса", listOf(S, M), Icons.Outlined.Search),
        TileDef("navigator", "Навигатор", "Полезное", "Открыть приложение навигации", listOf(S, M), Icons.Outlined.NearMe),
        TileDef("app", "Приложение", "Полезное", "Любое приложение на плитке", listOf(S, M), Icons.Outlined.Apps),
        TileDef("volume", "Громкость", "Переключатели", "Ползунок громкости музыки", listOf(T, M, W), Icons.Outlined.VolumeUp),
        TileDef("brightness", "Яркость", "Переключатели", "Ползунок яркости экрана", listOf(T, M, W), Icons.Outlined.LightMode),
        TileDef("night", "Ночной режим", "Переключатели", "Авто / всегда / выкл", listOf(S, M), Icons.Outlined.DarkMode),
        TileDef("voice", "Голосовые подсказки", "Переключатели", "Все / важные / нет", listOf(S, M), Icons.Outlined.RecordVoiceOver),
        TileDef("mapstyle", "Стиль карты", "Переключатели", "Тёмная / светлая", listOf(S, M), Icons.Outlined.Map),
        TileDef("traffic", "Пробки", "Переключатели", "Слой пробок Яндекса", listOf(S, M), Icons.Outlined.Traffic),
        TileDef("wifi", "Wi-Fi", "Переключатели", "Сеть и настройки Wi-Fi", listOf(S, M), Icons.Outlined.Wifi),
        TileDef("bluetooth", "Bluetooth", "Переключатели", "Состояние и настройки", listOf(S, M), Icons.Outlined.Bluetooth),
        TileDef("trip", "Поездка", "Машина", "Километры и время с включения", listOf(M, L, S), Icons.Outlined.Route),
        TileDef("reminder_next", "Ближайшее напоминание", "Машина", "Что скоро пора сделать", listOf(M, W), Icons.Outlined.EventNote),
        TileDef("reminders", "Напоминания", "Машина", "Список с прогрессом", listOf(L, XL, W), Icons.Outlined.Checklist),
        TileDef("memory", "Память", "Машина", "Занято; нажатие — освободить", listOf(M, S, L), Icons.Outlined.Memory),
    )
    fun def(type: String) = defs.firstOrNull { it.type == type }

    private const val DEFAULT = "clock:2x2,volume:1x2,brightness:1x2,weather:2x1,trip:2x1,memory:2x1,reminder_next:2x1,home:1x1,navigator:1x1,voice:1x1,night:1x1,mapstyle:1x1,traffic:1x1,wifi:1x1,bluetooth:1x1"

    fun load(ctx: android.content.Context): List<TileSpec> =
        (Prefs.str(ctx, Prefs.TILES_V2) ?: DEFAULT).split(',').mapNotNull { TileSpec.dec(it) }.filter { def(it.type) != null }
    fun save(ctx: android.content.Context, l: List<TileSpec>) = Prefs.put(ctx, Prefs.TILES_V2, l.joinToString(",") { it.enc() })
    fun reset(ctx: android.content.Context) = Prefs.put(ctx, Prefs.TILES_V2, null)
}

/** Greedy packing into a [cols]-wide grid: each tile goes to the first free spot, left-to-right, top-to-bottom. */
private fun pack(tiles: List<TileSpec>, cols: Int): Pair<List<Pair<Int, Int>>, Int> {
    val grid = ArrayList<BooleanArray>()
    fun free(r: Int, c: Int, w: Int, h: Int): Boolean {
        if (c + w > cols) return false
        for (y in r until r + h) { while (grid.size <= y) grid += BooleanArray(cols); for (x in c until c + w) if (grid[y][x]) return false }
        return true
    }
    val pos = tiles.map { t ->
        val w = t.w.coerceIn(1, cols)
        var r = 0
        while (true) {
            val c = (0..cols - w).firstOrNull { free(r, it, w, t.h) }
            if (c != null) { for (y in r until r + t.h) for (x in c until c + w) grid[y][x] = true; return@map c to r }
            r++
        }
        @Suppress("UNREACHABLE_CODE")
        0 to 0
    }
    return pos to grid.size
}

// ============================ page ============================
// Like the iPhone Control Center editor: hold a tile → edit mode → drag it anywhere (others make room),
// pull the corner handle to resize, «−» removes, «+ Добавить» at the bottom adds.

private data class TileItem(val id: Int, val spec: TileSpec)

@Composable
private fun jiggle(): State<Float> = rememberInfiniteTransition(label = "jiggle")
    .animateFloat(-0.8f, 0.8f, infiniteRepeatable(tween(140, easing = LinearEasing), RepeatMode.Reverse), label = "rot")

@Composable
fun TilesPage(s: LauncherState) {
    val ctx = LocalContext.current
    val v = s.settingsVersion
    val saved = remember(v) { TileCatalog.load(ctx) }
    var items by remember(saved) { mutableStateOf(saved.mapIndexed { i, t -> TileItem(i, t) }) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val edit = s.tilesEdit
    val editState = rememberUpdatedState(edit)
    fun commit() { TileCatalog.save(ctx, items.map { it.spec }); s.settingsVersion++ }

    var dragId by remember { mutableStateOf<Int?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var resizeId by remember { mutableStateOf<Int?>(null) }
    var lastSwapId by remember { mutableStateOf<Int?>(null) }
    var lastSwapAt by remember { mutableLongStateOf(0L) }
    val rot = if (edit && !InstantUi) jiggle() else null

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val cols = 8
            val gapDp = 12.dp
            val colWDp = (maxWidth - gapDp * (cols - 1)) / cols
            val rowHDp = (maxHeight - gapDp * 3) / 4
            val colW = with(density) { colWDp.toPx() }
            val rowH = with(density) { rowHDp.toPx() }
            val areaH = maxHeight
            val gap = with(density) { gapDp.toPx() }
            fun layout(list: List<TileItem>) = pack(list.map { it.spec }, cols)
            fun origin(p: Pair<Int, Int>) = Offset(p.first * (colW + gap), p.second * (rowH + gap))
            fun sizeOf(t: TileSpec): Size { val w = t.w.coerceIn(1, cols); return Size(w * colW + (w - 1) * gap, t.h * rowH + (t.h - 1) * gap) }

            /** While dragging: the tile under the finger's tile-centre gives up its place. */
            fun reorder(id: Int) {
                val list = items
                val idx = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
                val (pos, rows) = layout(list)
                val sz = sizeOf(list[idx].spec)
                val c = dragPos + Offset(sz.width / 2, sz.height / 2)
                var k = list.indices.firstOrNull { j ->
                    if (j == idx) return@firstOrNull false
                    val o = origin(pos[j]); val z = sizeOf(list[j].spec)
                    c.x in o.x..(o.x + z.width) && c.y in o.y..(o.y + z.height)
                }
                if (k == null && c.y > rows * (rowH + gap)) k = list.lastIndex
                if (k == null || k == idx) return
                val now = System.currentTimeMillis()
                if (list[k].id == lastSwapId && now - lastSwapAt < 450) return
                lastSwapId = list[k].id; lastSwapAt = now
                items = list.toMutableList().apply { add(k, removeAt(idx)) }
            }

            val (pos, rows) = layout(items)
            val scroll = rememberScrollState()
            val contentH = rowHDp * rows + gapDp * (rows - 1).coerceAtLeast(0) + if (edit) 90.dp else 0.dp
            Box(Modifier.fillMaxSize().verticalScroll(scroll, enabled = dragId == null && resizeId == null)) {
                Box(Modifier.fillMaxWidth().height(contentH.coerceAtLeast(areaH))) {
                    items.forEachIndexed { i, item ->
                        key(item.id) {
                            val t = item.spec
                            val anim by animateOffsetAsState(origin(pos[i]), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "tile")
                            val dragging = dragId == item.id
                            val sz = sizeOf(t)
                            val sign = if (item.id % 2 == 0) 1f else -1f
                            Box(Modifier
                                .offset { val o = if (dragging) dragPos else anim; IntOffset(o.x.roundToInt(), o.y.roundToInt()) }
                                .size(with(density) { sz.width.toDp() }, with(density) { sz.height.toDp() })
                                .zIndex(if (dragging) 2f else if (resizeId == item.id) 1f else 0f)
                                .graphicsLayer {
                                    val sc = if (dragging) 1.06f else 1f
                                    scaleX = sc; scaleY = sc
                                    rotationZ = if (!dragging && rot != null) rot.value * sign else 0f
                                    shadowElevation = if (dragging) 24f else 0f
                                    shape = GlassShape; clip = false
                                }
                                .pointerInput(item.id, v) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val go = if (editState.value) awaitTouchSlopOrCancellation(down.id) { ch, _ -> ch.consume() } != null
                                                 else awaitLongPressOrCancellation(down.id)?.also { s.tilesEdit = true } != null
                                        if (go) {
                                            val list = items
                                            val idx = list.indexOfFirst { it.id == item.id }
                                            if (idx >= 0) {
                                                dragPos = origin(layout(list).first[idx])
                                                dragId = item.id
                                                drag(down.id) { ch -> dragPos += ch.positionChange(); ch.consume(); reorder(item.id) }
                                                dragId = null; lastSwapId = null
                                                commit()
                                            }
                                        }
                                    }
                                }) {
                                TileView(s, t, tick, edit, onRemove = { items = items.filter { it.id != item.id }; commit() }) {
                                    // corner handle: pull to resize, snaps to the sizes this tile supports
                                    val def = TileCatalog.def(t.type)
                                    if (def != null && def.sizes.size > 1) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(30.dp).clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.22f)).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                                        .pointerInput(item.id, v) {
                                            var start = Size.Zero; var acc = Offset.Zero
                                            detectDragGestures(
                                                onDragStart = { start = sizeOf(items.first { it.id == item.id }.spec); acc = Offset.Zero; resizeId = item.id },
                                                onDragEnd = { resizeId = null; commit() },
                                                onDragCancel = { resizeId = null; commit() },
                                            ) { ch, amount ->
                                                ch.consume(); acc += amount
                                                val dw = (start.width + acc.x + gap) / (colW + gap)
                                                val dh = (start.height + acc.y + gap) / (rowH + gap)
                                                val best = def.sizes.minBy { (it.first - dw) * (it.first - dw) + (it.second - dh) * (it.second - dh) }
                                                val cur = items.first { it.id == item.id }.spec
                                                if (best != cur.w to cur.h) items = items.map { if (it.id == item.id) it.copy(spec = cur.copy(w = best.first, h = best.second)) else it }
                                            }
                                        }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.OpenInFull, "Размер", tint = Color.White, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 90f })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (items.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Плиток нет — нажмите «+ Добавить»", style = t(20f, C.Muted))
            }
        }
        if (edit) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).glass(shape = RoundedCornerShape(30.dp)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Перетаскивайте плитки · уголок — размер", style = t(14f, C.Text2), modifier = Modifier.padding(horizontal = 12.dp))
            GlassPill("По умолчанию") { TileCatalog.reset(ctx); s.settingsVersion++ }
            GlassPill("+ Добавить") { s.overlay = Overlay.TileCatalog }
            GlassPill("Готово", accent = true) { s.tilesEdit = false }
        } else Box(Modifier.align(Alignment.BottomEnd).padding(14.dp).size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape).clickable { s.tilesEdit = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Edit, "Изменить плитки", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun GlassPill(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (accent) Accent.color else Color.White.copy(alpha = 0.12f))
        .clickable(onClick = onClick).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(label, style = t(15f, if (accent) Color(0xFF15171A) else C.Text, FontWeight.Medium))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileView(s: LauncherState, tile: TileSpec, tick: Int, edit: Boolean, onRemove: () -> Unit, handle: @Composable BoxScope.() -> Unit) {
    val def = TileCatalog.def(tile.type) ?: return
    val v = s.settingsVersion
    val a = remember(tile, v, tick) { tileAction(s, tile) }
    Box(Modifier.fillMaxSize().glass()
        .combinedClickable(enabled = !edit, onClick = { a.onClick?.invoke() }, onLongClick = {})) {
        if (a.set != null) SliderTile(def, a, tile, edit)
        else Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) { TileContent(s, tile, def, a, tick) }
        if (edit) {
            Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(30.dp).clip(CircleShape).background(Color(0xFF3A3D40))
                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Remove, "Убрать", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            handle()
        }
    }
}

/** Volume / brightness: the whole tile is the slider (drag), like on iPhone. */
@Composable
private fun SliderTile(def: TileDef, a: TileState, tile: TileSpec, edit: Boolean) {
    val vertical = tile.h > tile.w
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(a.fraction ?: 0f) }
    // sync from the real system value only when the finger is NOT down (otherwise the 1-second tick fights the drag)
    LaunchedEffect(a.fraction, dragging) { if (!dragging) a.fraction?.let { local = it } }
    val setter = rememberUpdatedState(a.set)
    val pct = (local * 100).roundToInt()
    Box(Modifier.fillMaxSize().pointerInput(edit, vertical) {
        if (!edit) detectDragGestures(
            onDragStart = { dragging = true },
            onDragEnd = { dragging = false },
            onDragCancel = { dragging = false },
        ) { ch, amt ->
            ch.consume()
            val d = if (vertical) -amt.y / size.height else amt.x / size.width
            local = (local + d).coerceIn(0f, 1f); setter.value?.invoke(local)
        }
    }) {
        Box(Modifier.align(if (vertical) Alignment.BottomStart else Alignment.CenterStart)
            .then(if (vertical) Modifier.fillMaxWidth().fillMaxHeight(local.coerceIn(0f, 1f)) else Modifier.fillMaxHeight().fillMaxWidth(local.coerceIn(0f, 1f)))
            .background(Color.White.copy(alpha = 0.9f)))
        val onFill = if (vertical) local > 0.22f else local > 0.12f
        Icon(def.icon, null, tint = if (onFill) Color(0xFF15171A) else Color.White,
            modifier = Modifier.align(if (vertical) Alignment.BottomCenter else Alignment.CenterStart).padding(if (vertical) 18.dp else 20.dp).size(30.dp))
        if (!vertical) Text("$pct%", style = t(18f, if (local > 0.85f) Color(0xFF15171A) else C.Text, FontWeight.Medium),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp))
        else Text("$pct%", style = t(14f, if (local > 0.9f) Color(0xFF15171A) else C.Text2, FontWeight.Medium), modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
    }
}


/** What a tile shows and does right now. */
private class TileState(val value: String, val sub: String = "", val active: Boolean = false, val onClick: (() -> Unit)? = null, val fraction: Float? = null,
    val set: ((Float) -> Unit)? = null)

private fun toggle(s: LauncherState, key: String, def: Boolean, on: String = "Вкл", off: String = "Выкл", after: () -> Unit = {}): TileState {
    val ctx = s.activity
    val v = Prefs.bool(ctx, key, def)
    return TileState(if (v) on else off, active = v, onClick = { Prefs.put(ctx, key, !v); s.settingsVersion++; after() })
}

private fun tileAction(s: LauncherState, tile: TileSpec): TileState {
    val ctx = s.activity
    fun cycle(key: String, def: String, opts: List<Pair<String, String>>, after: () -> Unit = {}): TileState {
        val cur = Prefs.str(ctx, key, def)
        val i = opts.indexOfFirst { it.first == cur }.coerceAtLeast(0)
        return TileState(opts[i].second, active = cur != "off", onClick = {
            Prefs.put(ctx, key, opts[(i + 1) % opts.size].first); s.settingsVersion++; after()
        })
    }
    return when (tile.type) {
        "night" -> cycle(Prefs.NIGHT, "auto", listOf("auto" to "Авто", "on" to "Всегда", "off" to "Выкл")) { s.updateNight() }
        "voice" -> cycle(Prefs.VOICE_LEVEL, "important", listOf("all" to "Все", "important" to "Важные", "off" to "Нет"))
        "mapstyle" -> Prefs.str(ctx, Prefs.MAP_STYLE, "dark").let { st ->
            TileState(if (st == "light") "Светлая" else "Тёмная", onClick = { Prefs.put(ctx, Prefs.MAP_STYLE, if (st == "light") "dark" else "light"); s.settingsVersion++ })
        }
        "traffic" -> if (YandexMaps.enabled) toggle(s, Prefs.MAP_TRAFFIC, true) else TileState("Нужен ключ Яндекса", onClick = { s.overlay = Overlay.ApiKey })
        "heading" -> toggle(s, Prefs.MAP_HEADING, true)
        "limits" -> toggle(s, Prefs.LIMITS, true)
        "overspeed" -> toggle(s, Prefs.SOUND_OVERSPEED, true)
        "camera" -> toggle(s, Prefs.CAMERA_WARN, true)
        "parking" -> toggle(s, Prefs.PARKING, true)
        "kiosk" -> toggle(s, Prefs.KIOSK, true)
        "autoclean" -> toggle(s, Prefs.AUTO_CLEAN, true)
        "litemap" -> toggle(s, Prefs.LITE_MAP, false)
        "accent" -> TileState(Accent.options.firstOrNull { Color(it.first.toInt()) == Accent.color }?.second ?: "", onClick = {
            val i = Accent.options.indexOfFirst { Color(it.first.toInt()) == Accent.color }
            Accent.set(ctx, Accent.options[(i + 1) % Accent.options.size].first); s.settingsVersion++
        })
        "settings" -> TileState("Открыть", onClick = { s.overlay = Overlay.Settings })
        "memory" -> Optimizer.mem(ctx).let { m ->
            TileState("${(m.usedFraction * 100).toInt()}%", "${m.availMb} МБ свободно" + (Optimizer.lastResult?.let { " · $it" } ?: ""), fraction = m.usedFraction,
                onClick = { Apps.toast(ctx, "Освобождаю память…"); Optimizer.clean(ctx, s.media.sessionPackages()) { Apps.toast(ctx, it); s.settingsVersion++ } })
        }
        "storage" -> Optimizer.storage().let { (f, t) -> TileState("%.1f ГБ".format(f), "свободно из %.0f".format(t), fraction = if (t > 0) 1 - f / t else null, onClick = { s.go(Page.OPTIMIZE) }) }
        "animations" -> Optimizer.animationScale(ctx).let { sc -> TileState(if (sc == 0f) "Выкл" else "${sc}×", if (sc <= 0.5f) "ускорено" else "можно 0,5×", active = sc <= 0.5f,
            onClick = { Optimizer.openDeveloperOptions(ctx) }) }
        "reminder_next" -> Reminders.states(ctx, s.vehicle.odometerKm).firstOrNull().let { st ->
            if (st == null) TileState("Нет напоминаний", "нажмите, чтобы добавить", onClick = { s.go(Page.REMINDERS) })
            else TileState(st.r.title, st.left, active = st.due || st.soon, fraction = st.fraction, onClick = { s.go(Page.REMINDERS) })
        }
        "reminders" -> TileState("", onClick = { s.go(Page.REMINDERS) })
        "odometer" -> TileState(Reminders.fmtKm(s.vehicle.odometerKm), if (s.vehicle.odometerSet) "по GPS" else "нажмите, чтобы указать", onClick = { s.overlay = Overlay.Odometer })
        "clock" -> TileState("")
        "speed" -> TileState("${s.vehicle.speedKmh}")
        "weather" -> TileState(s.weather.tempC?.let { "$it°" } ?: "--°", ForecastRepo.describe(s.weather.code), onClick = { s.overlay = Overlay.Weather })
        "trip" -> {
            val km = s.vehicle.tripM / 1000
            val min = s.vehicle.movingMs / 60_000
            val avg = if (s.vehicle.movingMs > 60_000) (km / (s.vehicle.movingMs / 3_600_000.0)).toInt() else 0
            TileState("%.1f км".format(km), "в движении ${min} мин" + if (avg > 0) " · ср. $avg км/ч" else "")
        }
        "home" -> s.nav.home.let { h ->
            if (h == null) TileState("Не задан", "нажмите, чтобы указать", onClick = { s.overlay = Overlay.Search(setHome = true) })
            else TileState(distanceTo(s, h.lat, h.lon) ?: "Маршрут", h.name, onClick = { s.nav.routeTo(h, s.vehicle.location); s.go(Page.HOME) })
        }
        "places" -> TileState("")
        "search" -> TileState("Найти адрес", onClick = { s.overlay = Overlay.Search() })
        "navigator" -> TileState(Apps.label(ctx, Apps.resolve(ctx, Prefs.NAV, Known.NAV)), onClick = { s.openNavigator() })
        "volume" -> {
            val am = ctx.getSystemService(AudioManager::class.java)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC); val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            TileState("${cur * 100 / max}%", fraction = cur.toFloat() / max, set = { f ->
                val target = (f * max).roundToInt().coerceIn(0, max)
                // TEYES часто игнорирует абсолютную установку — сначала пробуем её, потом добираем шагами (как физические +/-)
                runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                var c = am.getStreamVolume(AudioManager.STREAM_MUSIC); var guard = 0
                while (c != target && guard++ <= max + 1) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (c < target) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                    val n = am.getStreamVolume(AudioManager.STREAM_MUSIC); if (n == c) break; c = n
                }
            })
        }
        "brightness" -> {
            if (!Settings.System.canWrite(ctx)) TileState("Нужно разрешение", "нажмите, чтобы выдать", onClick = {
                Apps.openFirst(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) else {
                val b = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
                TileState("${(b * 100 / 255)}%", fraction = b / 255f, set = { f -> runCatching {
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (f * 255).roundToInt().coerceIn(5, 255))
                } })
            }
        }
        "sun" -> s.vehicle.location?.let { l -> Sun.times(l.latitude, l.longitude) }?.let { (r, st) -> TileState("$st", "восход $r") } ?: TileState("—", "нет GPS")
        "compass" -> s.vehicle.location.let { l ->
            val dirs = listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
            val b = s.vehicle.bearing
            TileState("${dirs[((b + 22.5f) / 45f).toInt() % 8]} ${b.toInt()}°", if (l != null && l.hasAltitude()) "высота ${l.altitude.toInt()} м" else "")
        }
        "wifi" -> TileState(if (s.status.online) "В сети" else "Нет сети", active = s.status.online, onClick = {
            Apps.openFirst(ctx, Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS))
        })
        "bluetooth" -> TileState(if (s.status.bluetoothOn) "Вкл" else "Выкл", active = s.status.bluetoothOn, onClick = {
            Apps.openFirst(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        })
        "wheel" -> WheelKeys.enabled(ctx).let { on -> TileState(if (on) "Жесты вкл" else "Выкл", active = on, onClick = { s.overlay = Overlay.Wheel }) }
        "app" -> TileState(Apps.label(ctx, tile.arg), onClick = { Apps.launch(ctx, tile.arg) })
        else -> TileState("")
    }
}

private fun distanceTo(s: LauncherState, lat: Double, lon: Double): String? {
    val l = s.vehicle.location ?: return null
    val r = FloatArray(1); android.location.Location.distanceBetween(l.latitude, l.longitude, lat, lon, r)
    return if (r[0] >= 1000) "%.1f км".format(r[0] / 1000) else "${r[0].toInt()} м"
}

// ============================ contents ============================

private val sliders = setOf("volume", "brightness")

@Composable
private fun TileContent(s: LauncherState, tile: TileSpec, def: TileDef, a: TileState, tick: Int) {
    val ctx = LocalContext.current
    val big = tile.h >= 2
    val tiny = tile.w == 1 && tile.h == 1
    when (tile.type) {
        "clock" -> {
            val now = Date()
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = if (tiny) Alignment.CenterHorizontally else Alignment.Start) {
                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), style = t(if (big) 92f else if (tiny) 34f else 54f, C.Text, FontWeight.Light))
                Text(SimpleDateFormat(if (tiny) "d MMM" else "EEEE, d MMMM", Locale("ru")).format(now).replaceFirstChar { it.titlecase(Locale("ru")) },
                    style = t(if (big) 20f else 14f, C.Text2), maxLines = 1)
            }
        }
        "speed" -> {
            val limit = s.vehicle.limits.limit
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = if (tiny) Alignment.CenterHorizontally else Alignment.Start) {
                    Text(a.value, style = t(if (big) 100f else if (tiny) 40f else 56f, C.Text, FontWeight.Light).copy(lineHeight = (if (big) 104 else 58).sp))
                    Text("км/ч", style = t(14f, C.Text2))
                }
                if (limit != null && !tiny) Box(Modifier.size(if (big) 84.dp else 52.dp).clip(CircleShape).background(Color.White).border(if (big) 9.dp else 6.dp, C.SignRed, CircleShape),
                    contentAlignment = Alignment.Center) { Text("$limit", style = t(if (big) 30f else 19f, Color(0xFF111111), FontWeight.SemiBold)) }
            }
        }
        "weather" -> if (tiny) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(weatherIconFor(s.weather.code), null, tint = C.Yellow, modifier = Modifier.size(36.dp))
            Text(a.value, style = t(24f, C.Text, FontWeight.Light))
        } else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Icon(weatherIconFor(s.weather.code), null, tint = C.Yellow, modifier = Modifier.size(if (big) 68.dp else 44.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(a.value, style = t(if (big) 60f else 36f, C.Text, FontWeight.Light))
                Text(a.sub, style = t(14f, C.Text2), maxLines = 1)
            }
        }
        "places" -> Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIcon(def.icon, false, 36.dp); Spacer(Modifier.width(10.dp)); Text(def.title, style = t(15f, C.Text2))
            }
            Spacer(Modifier.height(10.dp))
            val list = listOfNotNull(s.nav.home?.let { Place("Домой", it.description, it.lat, it.lon) to it }) + s.nav.favorites.map { it to it }
            if (list.isEmpty()) Text("Добавьте места ☆ в поиске", style = t(14f, C.Muted))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.take(if (big) 8 else 3).forEach { (label, dest) ->
                    Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.12f))
                        .clickable { s.nav.routeTo(dest, s.vehicle.location); s.go(Page.HOME) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(label.name.take(18), style = t(14f, C.Text, FontWeight.Medium))
                    }
                }
            }
        }
        "reminders" -> Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIcon(def.icon, false, 36.dp); Spacer(Modifier.width(10.dp)); Text(def.title, style = t(15f, C.Text2))
            }
            Spacer(Modifier.height(10.dp))
            val st = Reminders.states(ctx, s.vehicle.odometerKm)
            if (st.isEmpty()) Text("Нет напоминаний — нажмите, чтобы добавить", style = t(14f, C.Muted))
            st.take(if (big) 3 else 1).forEach { r ->
                Text(r.r.title, style = t(15f, C.Text, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.left, style = t(13f, if (r.due) Color(0xFFFF8A80) else C.Text2), maxLines = 1)
                ProgressLine(r.fraction, if (r.due) C.GuideRed else if (r.soon) C.Yellow else C.GuideGreen, Modifier.padding(top = 4.dp, bottom = 8.dp))
            }
        }
        "app" -> {
            val ic = remember(tile.arg) { Apps.icon(ctx, tile.arg) }
            if (tiny) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (ic != null) Image(ic, null, Modifier.size(50.dp), filterQuality = FilterQuality.High)
                Spacer(Modifier.height(6.dp))
                Text(a.value, style = t(13f, C.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (ic != null) Image(ic, null, Modifier.size(60.dp), filterQuality = FilterQuality.High)
                Spacer(Modifier.width(14.dp))
                Text(a.value, style = t(18f, C.Text, FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        else -> when {
            // 1×1 — round Control-Center button + label
            tiny -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                GlassIcon(def.icon, a.active, 50.dp)
                Spacer(Modifier.height(8.dp))
                Text(def.title, style = t(12.5f, C.Text, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.value, style = t(12f, C.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // wide — icon + title + value, progress when there is one
            !big -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                GlassIcon(def.icon, a.active, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (a.fraction != null || tile.type in setOf("trip", "odometer", "reminder_next", "memory", "storage")) a.value else def.title,
                        style = t(19f, C.Text, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val second = if (a.fraction != null || tile.type in setOf("trip", "odometer", "reminder_next", "memory", "storage")) a.sub.ifEmpty { def.title } else a.value
                    Text(second, style = t(14f, C.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    a.fraction?.let { ProgressLine(it, if (it > 0.88f) C.GuideRed else C.Yellow, Modifier.padding(top = 6.dp)) }
                }
            }
            // big — icon on top, large value at the bottom
            else -> Column(Modifier.fillMaxSize()) {
                GlassIcon(def.icon, a.active, 52.dp)
                Spacer(Modifier.weight(1f))
                Text(def.title, style = t(15f, C.Text2), maxLines = 1)
                Text(a.value, style = t(34f, C.Text, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (a.sub.isNotEmpty()) Text(a.sub, style = t(14f, C.Text2), maxLines = 2, overflow = TextOverflow.Ellipsis)
                a.fraction?.let { ProgressLine(it, if (it > 0.88f) C.GuideRed else C.Yellow, Modifier.padding(top = 8.dp)) }
            }
        }
    }
}


// ============================ catalog ============================

@Composable
fun TileCatalogScreen(s: LauncherState) {
    val ctx = LocalContext.current
    fun add(spec: TileSpec) { TileCatalog.save(ctx, TileCatalog.load(ctx) + spec); s.settingsVersion++; s.tilesEdit = true; s.go(Page.TILES) }
    Column(Modifier.fillMaxSize()) {
        OverlayHeader("Добавить плитку") { s.overlay = null }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TileCatalog.groups.forEach { g ->
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Section(g)
                    TileCatalog.defs.filter { it.group == g }.forEach { d ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape)
                            .clickable {
                                val (w, h) = d.sizes.first()
                                if (d.type == "app") s.pick("Приложение на плитке") { p -> add(TileSpec("app", w, h, p)) }
                                else { add(TileSpec(d.type, w, h)); s.overlay = null }
                            }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(d.icon, null, tint = C.Text2, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.title, style = t(16f, C.Text, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(d.desc, style = t(13f, C.Muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Outlined.Add, null, tint = C.Yellow, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A tile used as a block in the home screen's side column (always the wide 2×1 look). */
@Composable
fun HomeTile(s: LauncherState, type: String, arg: String?, modifier: Modifier) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    Box(modifier) { TileView(s, TileSpec(type, 2, 1, arg), tick, edit = false, onRemove = {}) {} }
}
