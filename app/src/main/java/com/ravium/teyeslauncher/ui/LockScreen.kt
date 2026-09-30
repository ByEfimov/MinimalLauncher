package com.ravium.teyeslauncher.ui

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
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

/**
 * Activation screen — fully offline. Left: this head unit's 6-digit code. Right: enter the 6-digit
 * activation code the owner gives back. No internet needed.
 */
@Composable
fun LockScreen(s: LauncherState) {
    val ctx = LocalContext.current
    val code = remember { License.deviceId(ctx) }
    val clip = ctx.getSystemService(ClipboardManager::class.java)
    var input by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Bg, C.BgBottom))), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 620.dp).clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Lock, null, tint = C.Yellow, modifier = Modifier.size(50.dp))
            Spacer(Modifier.height(14.dp))
            Text("Активация Minimal Drive", style = t(26f, C.Text, FontWeight.Medium))
            Spacer(Modifier.height(8.dp))
            Text("1. Сообщите владельцу код магнитолы.\n2. Введите код активации, который он пришлёт.\nИнтернет не нужен.",
                style = t(16f, C.Text2), textAlign = TextAlign.Center)
            Spacer(Modifier.height(26.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("КОД МАГНИТОЛЫ", style = t(12f, C.Muted, FontWeight.Medium).copy(letterSpacing = 1.5.sp))
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF17191B)).border(Hairline, C.Stroke, RoundedCornerShape(14.dp))
                        .clickable { clip.setPrimaryClip(android.content.ClipData.newPlainText("code", code)); Apps.toast(ctx, "Скопировано") }
                        .padding(horizontal = 24.dp, vertical = 16.dp)) {
                        Text(code, style = t(42f, C.Text, FontWeight.SemiBold).copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("нажмите, чтобы скопировать", style = t(12f, C.Muted))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("КОД АКТИВАЦИИ", style = t(12f, C.Muted, FontWeight.Medium).copy(letterSpacing = 1.5.sp))
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = input,
                        onValueChange = { v ->
                            input = v.filter { it.isDigit() }.take(6)
                            License.error = false
                            if (input.length == 6) License.applyCode(input)
                        },
                        modifier = Modifier.width(220.dp).clip(RoundedCornerShape(14.dp))
                            .border(1.5.dp, if (License.error) C.GuideRed else C.Stroke, RoundedCornerShape(14.dp)),
                        placeholder = { Text("", style = t(42f, C.Muted)) },
                        textStyle = t(42f, C.Text, FontWeight.SemiBold).copy(fontFamily = FontFamily.Monospace, letterSpacing = 8.sp, textAlign = TextAlign.Center),
                        singleLine = true,
                        isError = License.error,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF17191B), unfocusedContainerColor = Color(0xFF17191B), cursorColor = C.Yellow,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, errorContainerColor = Color(0xFF17191B),
                            errorIndicatorColor = Color.Transparent),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (License.error) Text("Неверный код — проверьте цифры", style = t(14f, C.GuideRed))
                    else Text("6 цифр", style = t(12f, C.Muted))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Код действует только для этой магнитолы", style = t(13f, C.Muted))
        }
    }
}
