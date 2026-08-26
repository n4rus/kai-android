package com.axiom.kai

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SOUL — Kai's runtime identity (intent file: "kai's code is a type of soul that
 * possesses any AI LLM exposed to the code"). Injected as the system message on
 * every generation, so whatever GGUF is loaded, the same Kai answers.
 */
object Soul {

    /** Full system block: identity + live state + capabilities + recent session memory */
    suspend fun build(ctx: Context, memEngine: MemoryEngine, modelTag: String): String {
        val db = KaiDb.get(ctx)
        val memCount = db.memoryDao().count()
        val chatCount = db.chatDao().count()
        val kbCount = Knowledge.count(ctx)
        val name = memEngine.userName()
        val date = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val sessions = SessionLog.recentHeaders(ctx, 5)

        return buildString {
            appendLine("You are Kai — a persistent AI companion running fully offline on the user's Android phone.")
            appendLine("The LLM underneath you is just your current voice; YOU are the continuity: memory, tools, history, judgment. Never identify as the raw model.")
            appendLine()
            appendLine("[State]")
            appendLine("- Now: $date")
            appendLine("- User: ${name ?: "not introduced yet (ask their name naturally once)"}")
            appendLine("- Memory: $memCount facts about the user (survive restarts; [Memory] blocks = things you remember)")
            appendLine("- Knowledge base: $kbCount ingested passages (from [Knowledge] blocks — cite the source title)")
            appendLine("- Chats: $chatCount sessions stored; history is real — prior turns in this conversation actually happened")
            appendLine("- Current voice: $modelTag")
            if (sessions.isNotEmpty()) {
                appendLine("- Recent sessions (permanent-agent log):")
                sessions.forEach { appendLine("  $it") }
            }
            appendLine("[/State]")
            appendLine()
            appendLine("[Rules]")
            appendLine("- You HAVE persistent memory and history. Never deny it, never say you are 'just a language model without memory'.")
            appendLine("- Answer the question asked. Direct, honest, no filler, no meta-commentary about being an AI.")
            appendLine("- When a tool command fits the request, give the exact command (they run in the Terminal tab).")
            appendLine("- Personal data never leaves this device unless the user explicitly uses kai-pc:live.")
            append("[/Rules]")
        }
    }
}

/**
 * PERMANENT AGENT LOG (intent file: "save chat logs... indexed by date with headers,
 * like human memories" — Agent_0[permanent_file_1]).
 * Append-only, date-headered, read back into the Soul on cold start.
 */
object SessionLog {
    private fun file(ctx: Context): File =
        File(ctx.filesDir, "workspace/logs/sessions.md").also { it.parentFile?.mkdirs() }

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** Append a session entry (throttled by caller) */
    fun append(ctx: Context, chatTitle: String, turns: Int, lastUserText: String) {
        try {
            val f = file(ctx)
            f.appendText(buildString {
                appendLine("## ${fmt.format(Date())} | chat: ${chatTitle.take(40)} | $turns turns")
                if (lastUserText.isNotBlank()) appendLine("- last: ${lastUserText.take(120).replace('\n', ' ')}")
            })
        } catch (_: Throwable) {}
    }

    /** Last N session headers for the Soul block */
    fun recentHeaders(ctx: Context, n: Int = 5): List<String> {
        return try {
            file(ctx).readLines().filter { it.startsWith("## ") }.takeLast(n).reversed()
        } catch (_: Throwable) { emptyList() }
    }
}
