package com.axiom.kai

import android.content.Context

object Lang {
    private const val PREFS = "kai_lang"
    private const val KEY = "lang" // en or pt
    fun get(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "en") ?: "en"
    fun set(ctx: Context, v: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply() }
    fun isPt(ctx: Context) = get(ctx) == "pt"
    fun toggle(ctx: Context): String { val n = if (get(ctx)=="en") "pt" else "en"; set(ctx,n); return n }
    fun t(ctx: Context, en: String, pt: String): String = if (isPt(ctx)) pt else en
}
