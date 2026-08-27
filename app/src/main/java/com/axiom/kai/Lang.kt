package com.axiom.kai

import android.content.Context
import androidx.compose.runtime.mutableStateOf

object Lang {
    private const val PREFS = "kai_lang"
    private const val KEY = "lang" // en or pt
    var version = mutableStateOf(0)
        private set
    fun get(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "en") ?: "en"
    fun set(ctx: Context, v: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply(); version.value++ }
    fun isPt(ctx: Context) = get(ctx) == "pt"
    fun toggle(ctx: Context): String { val n = if (get(ctx)=="en") "pt" else "en"; set(ctx,n); version.value++; return n }
    fun t(ctx: Context, en: String, pt: String): String = if (isPt(ctx)) pt else en
    // composable helper that observes version to trigger recomposition
    @androidx.compose.runtime.Composable
    fun tC(ctx: Context, en: String, pt: String): String {
        version.value
        return t(ctx, en, pt)
    }
}
