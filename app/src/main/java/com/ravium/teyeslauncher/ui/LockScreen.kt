package com.ravium.teyeslauncher.ui

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravium.teyeslauncher.*
import kotlinx.coroutines.delay

/**
 * Shown instead of the launcher until the head unit is activated. The 6-digit device code here is what the
 * owner turns into a 6-digit activation code; enter it (or it arrives on its own via the service) to unlock.
 */
@Composable
fun LockScreen(s: LauncherState) {
    val ctx = LocalContext.current
    val code = remember { License.deviceId(ctx) }
    val clip = ctx.getSystemService(ClipboardManager::class.java)
    var input by remember { mutableStateOf("") }
    // while locked, re-check automatically (owner may approve via the service)
    LaunchedEffect(Unit) { while (!License.activated) { delay(20_000); if (!License.busy) License.checkOnline() } }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Bg, C.BgBottom))), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Lock, null, tint = C.Yellow, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(14.dp))
            Text("Minimal Drive не активирован", style = t(26f, C.Text, FontWeight.Medium))
            Spacer(Modifier.height(6.dp))
            Text("Сообщите код магнитолы владельцу и введите полученный код активации. После активации интернет не нужен.",
                style = t(15f, C.Text2), textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("КОД МАГНИТОЛЫ", style = t(12f, C.Muted, FontWeight.Medium).copy(letterSpacing = 1.5.sp))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF17191B)).border(Hairline, C.Stroke, RoundedCornerShape(14.dp))
                        .clickable { clip.setPrimaryClip(android.content.ClipData.newPlainText("code", code)); Apps.toast(ctx, "Скопировано") }
                        .padding(horizontal = 22.dp, vertical = 14.dp)) {
                        Text(code, style = t(40f, C.Text, FontWeight.SemiBold).copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("КОД АКТИВАЦИИ", style = t(12f, C.Muted, FontWeight.Medium).copy(letterSpacing = 1.5.sp))
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = input, onValueChange = { v -> input = v.filter { it.isDigit() }.take(6); if (input.length == 6) License.applyCode(input) },
                        modifier = Modifier.width(200.dp).clip(RoundedCornerShape(14.dp)),
                        placeholder = { Text("", style = t(40f, C.Muted).copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp), textAlign = TextAlign.Center) },
                        textStyle = t(40f, C.Text, FontWeight.SemiBold).copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp, textAlign = TextAlign.Center),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF17191B), unfocusedContainerColor = Color(0xFF17191B), cursorColor = C.Yellow,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            if (License.status.isNotEmpty())
                Text(License.status, style = t(15f, if (License.activated) C.GuideGreen else C.Muted), textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.height(56.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape)
                .clickable(enabled = !License.busy) { License.checkOnline() }.padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                Text(if (License.busy) "Проверяю…" else "Проверить активацию", style = t(16f, C.Text2, FontWeight.Medium))
            }
        }
    }
}
