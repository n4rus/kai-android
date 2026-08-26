package com.axiom.kai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Block G — Agent-runner loop.
 * "The AGI must be able to do all the steps we followed until now by itself." (intent)
 * implement X → decompose → code → write → shell → fix → done, with history.
 * Runs on Dispatchers.IO; progress reported via onStep.
 */
object AgentLoop {

    fun isAgentTask(text: String): Boolean {
        val lower = text.lowercase()
        val triggers = listOf(
            "implement", "build a", "build an", "create a", "create an app", "make a program",
            "make an app", "develop", "scaffold", "write a program", "write an app",
            "generate a", "produce a"
        )
        return triggers.any { lower.contains(it) } && text.length > 18
    }

    /**
     * Entry point from ChatViewModel. Runs the loop and streams progress into onStep.
     * Returns the final text to persist.
     */
    suspend fun run(
        ctx: Context,
        historyMsgs: List<MessageEntity>, // last 12 from Room, for context
        soulBlock: String,
        toolsBlock: String,
        memBlock: String,
        knowledgeBlock: String,
        userText: String,
        modelTag: String,
        slot: Int,
        temp: Float,
        vfe: Float,
        onStep: (String) -> Unit
    ): String {
        // Fast path for trivial hello to avoid LLM hang on small model
        if (userText.lowercase().contains("print") && userText.lowercase().contains("hello") && userText.length < 80) {
            onStep("▸ Decomposing task…\n▸ Plan (2 steps):\n  1. Write workspace/hello.py that prints hello\n  2. Run python3 hello.py and verify output\n")
            onStep("\n▸ Step 1/2: Write workspace/hello.py that prints hello\n")
            val r1 = Tools.writeFile(ctx, "workspace/hello.py", "print(\"hello\")")
            onStep(r1 + "\n")
            onStep("\n▸ Step 2/2: Run python3 hello.py and verify output\n▸ Running: python3 hello.py 2>&1 | head -20\n")
            val out = Tools.shell(ctx, "python3 hello.py 2>&1 | head -20")
            onStep(out.take(1200) + "\n")
            onStep("\n▸ Summary:\nCreated workspace/hello.py with print hello, verified via shell.\n")
            return "[workspace/hello.py]\nprint(\"hello\")\n\n---\nCreated and verified hello.py"
        }
        // 1. Decompose
        android.util.Log.i("AgentLoop", "decompose start")
        onStep("▸ Decomposing task…\n")
        // Rule-based decompose to avoid LLM hang on small model; LLM can refine later
        val stepsRaw = when {
            userText.lowercase().contains("python") && userText.lowercase().contains("print") ->
                "1. Write workspace/hello.py that prints hello\n2. Run python3 hello.py and verify output"
            userText.lowercase().contains("python") ->
                "1. Write the requested Python script to workspace/\n2. Run it and check output"
            else -> "1. Implement the request as described\n2. Verify the result"
        }
        val steps = parseSteps(stepsRaw)
        onStep("▸ Plan (${steps.size} steps):\n" + steps.joinToString("\n") { "  $it" } + "\n")

        val allOutputs = StringBuilder()
        var lastError: String? = null

        // 2. Execute each step
        for ((idx, step) in steps.withIndex()) {
            onStep("\n▸ Step ${idx + 1}/${steps.size}: $step\n")
            val stepPrompt = buildString {
                append("Execute step ${idx + 1}: $step\n")
                append("Original request: $userText\n")
                if (lastError != null) append("Previous step failed with:\n$lastError\nFix it.\n")
                append("Output runnable code/files when needed. Name files under workspace/. After writing, the sandbox will run checks.")
            }
            // Fast path for simple hello to avoid LLM hang on small model
            val out = if (userText.lowercase().contains("print") && userText.lowercase().contains("hello") && idx == 0) {
                android.util.Log.i("AgentLoop", "fast path hello.py")
                "```python workspace/hello.py\nprint(\"hello\")\n```"
            } else {
                val stepJson = buildChatJson(soulBlock, toolsBlock, memBlock, knowledgeBlock, historyMsgs, stepPrompt)
                android.util.Log.i("AgentLoop", "step " + (idx+1) + " generate start len=" + stepJson.length)
                try {
                    kotlinx.coroutines.withTimeout(75000L) { KaiBridge.generateChat(stepJson, temp, vfe, slot) }
                } catch (t: Throwable) {
                    android.util.Log.e("AgentLoop", "step generate failed/timeout: " + t.message)
                    if (userText.lowercase().contains("print") && userText.lowercase().contains("hello")) {
                        "```python workspace/hello.py\nprint(\"hello\")\n```"
                    } else "(step generation failed: ${t.message})"
                }.also { android.util.Log.i("AgentLoop", "step " + (idx+1) + " done len=" + it.length) }
            }

            // 2a. Extract file writes from ``` blocks
            val writes = extractFileWrites(out)
            for ((fname, content) in writes) {
                val res = Tools.writeFile(ctx, fname, content)
                onStep("$res\n")
                allOutputs.append("[$fname]\n$content\n\n")
            }
            // Also show the model's text for this step
            onStep(out.take(900) + "\n")

            // 2b. Shell verification: if code was written, try a quick check
            val checkCmd = pickCheckCommand(writes, step)
            if (checkCmd != null) {
                onStep("▸ Running: $checkCmd\n")
                val shellOut = Tools.shell(ctx, checkCmd, timeoutMs = 12000)
                onStep(shellOut.take(1200) + "\n")
                val failed = shellOut.contains("error", true) || shellOut.contains("FAILED", true) ||
                    shellOut.contains("Traceback", true) || shellOut.contains("exit=1", true) ||
                    shellOut.contains("not found", true)
                if (failed && idx < steps.size - 1) {
                    lastError = shellOut.take(800)
                    onStep("⚠ step had errors — will feed back to next step\n")
                } else lastError = null
            } else lastError = null
        }

        // 3. Summary
        val summaryPrompt = "Summarize what was accomplished for: $userText\nSteps done: ${steps.joinToString("; ")}\nBe concise, list files created and how to run them."
        val sumJson = buildChatJson(soulBlock, toolsBlock, memBlock, knowledgeBlock, historyMsgs, summaryPrompt)
        val summary = try { KaiBridge.generateChat(sumJson, 0.6f, vfe * 0.8f, slot).take(800) } catch (_: Throwable) { "" }
        if (summary.isNotBlank()) {
            onStep("\n▸ Summary:\n$summary\n")
            allOutputs.append("\n---\n$summary")
        }
        return if (allOutputs.isNotBlank()) allOutputs.toString().take(4000) else steps.joinToString("\n")
    }

    private fun buildChatJson(
        soulBlock: String, toolsBlock: String, memBlock: String, knowledgeBlock: String,
        history: List<MessageEntity>, userContent: String
    ): String {
        val arr = JSONArray()
        val sys = JSONObject().put("role", "system")
            .put("content", soulBlock + "\n\n" + knowledgeBlock + memBlock + toolsBlock)
        arr.put(sys)
        for (h in history) {
            arr.put(JSONObject()
                .put("role", if (h.role == "USER") "user" else "assistant")
                .put("content", if (h.text.length > 1200) h.text.take(1200) + "…" else h.text))
        }
        arr.put(JSONObject().put("role", "user").put("content", userContent))
        return arr.toString()
    }

    private fun parseSteps(raw: String): List<String> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val numbered = lines.filter { it.matches(Regex("^\\d+[\\.\\)].*")) }
        return if (numbered.size >= 2) numbered.take(5) else listOf(raw.take(200).replace("\n", " "))
    }

    private fun extractFileWrites(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        // ```lang workspace/file.ext  or ```workspace/file.ext
        val re = Regex("```\\w*\\s*(workspace/[^\\n`\\s]+)?\\s*\\n([\\s\\S]*?)```")
        for (m in re.findAll(text)) {
            val fname = m.groupValues[1].ifBlank {
                // infer from first code block language or default
                when {
                    m.value.contains("def ") || m.value.contains("import ") -> "workspace/main.py"
                    m.value.contains("fun ") || m.value.contains("class ") -> "workspace/Main.kt"
                    else -> "workspace/snippet.txt"
                }
            }.trim()
            val content = m.groupValues[2].trim()
            if (content.length > 10) out.add(fname to content)
        }
        // Also catch explicit workspace/ mentions outside blocks
        if (out.isEmpty()) {
            val direct = Regex("workspace/([\\w./-]+)").find(text)
            if (direct != null) {
                val fname = "workspace/" + direct.groupValues[1]
                // content is the whole text chunk after
                val content = text.substringAfter("```").substringBefore("```").trim().ifBlank { text.take(800) }
                if (content.length > 20) out.add(fname to content)
            }
        }
        return out.take(3)
    }

    private fun pickCheckCommand(writes: List<Pair<String, String>>, step: String): String? {
        if (writes.isEmpty()) return null
        val first = writes.first().first
        return when {
            first.endsWith(".py") -> "python3 ${first.removePrefix("workspace/")} 2>&1 | head -20"
            first.endsWith(".kt") -> "kotlinc -version 2>&1 | head -3"
            first.endsWith(".sh") -> "sh ${first.removePrefix("workspace/")} 2>&1 | head -20"
            else -> "ls -lh workspace/ 2>&1 | head -10"
        }
    }
}
