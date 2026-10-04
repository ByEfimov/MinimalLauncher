# Мост WebView ↔ Kotlin для векторной карты 2ГИС (MapGL). Методы вызываются из JS —
# их имена нельзя обфусцировать.
-keep class com.ravium.teyeslauncher.ui.Gis2Bridge { *; }
-keepclassmembers class com.ravium.teyeslauncher.ui.Gis2Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
