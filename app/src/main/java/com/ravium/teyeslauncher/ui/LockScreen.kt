package com.ravium.teyeslauncher.ui

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravium.teyeslauncher.*
import kotlinx.coroutines.delay

/**
 * Shown instead of the launcher until the head unit is activated. The device code here is what the owner
 * approves; once approved, «Проверить активацию» fetches the code (or paste it), and everything unlocks.
 */
@Composable
fun LockScreen(s: LauncherState) {
    val ctx = LocalContext.current
    val code = remember { License.deviceCodePretty(ctx) }
    val clip = ctx.getSystemService(ClipboardManager::class.java)
    // while locked, re-check automatically every 20 s (owner approves → unlocks on its own)
    LaunchedEffect(Unit) { while (!License.activated) { delay(20_000); if (!License.busy) License.checkOnline() } }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Bg, C.BgBottom))), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Lock, null, tint = C.Yellow, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text("Minimal Drive не активирован", style = t(28f, C.Text, FontWeight.Medium))
            Spacer(Modifier.height(8.dp))
            Text("Сообщите этот код владельцу, чтобы активировать магнитолу. После активации интернет больше не нужен.",
                style = t(16f, C.Text2), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(22.dp))
            Text("Код магнитолы", style = t(13f, C.Muted, FontWeight.Medium).copy(letterSpacing = 1.5.sp))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF17191B)).border(Hairline, C.Stroke, RoundedCornerShape(14.dp))
                .clickable { clip.setPrimaryClip(android.content.ClipData.newPlainText("code", code)); Apps.toast(ctx, "Код скопирован") }
                .padding(horizontal = 26.dp, vertical = 16.dp)) {
                Text(code, style = t(34f, C.Text, FontWeight.SemiBold).copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp))
            }
            Spacer(Modifier.height(22.dp))
            if (License.status.isNotEmpty()) {
                Text(License.status, style = t(15f, if (License.activated) C.GuideGreen else C.Muted), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.height(60.dp).clip(CardShape).background(C.YellowBg).border(1.5.dp, C.YellowBorder, CardShape)
                    .clickable(enabled = !License.busy) { License.checkOnline() }.padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                    Text(if (License.busy) "Проверяю…" else "Проверить активацию", style = t(18f, C.Text, FontWeight.Medium))
                }
                Box(Modifier.height(60.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape)
                    .clickable {
                        val pasted = clip.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
                        if (pasted.isNullOrEmpty()) Apps.toast(ctx, "Скопируйте код активации, затем нажмите сюда")
                        else License.applyCode(pasted)
                    }.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    Text("Вставить код активации", style = t(18f, C.Text2, FontWeight.Medium))
                }
            }
        }
    }
}
