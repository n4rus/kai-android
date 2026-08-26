package com.axiom.kai

/**
 * Block B — Skill registry.
 * The "soul" knows HOW to use its tools. Each skill is a prompt template + trigger set.
 * Detection: keyword hit → embedding cosine fallback (TextEmbed, same 128-dim as memories).
 * Intent file: "If python is necessary, python info… debug skill and tests are also part
 * of mind mechanics persisting iterations until the task is completed."
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val triggers: List<String>, // lowercase keywords
    val extraPrompt: String     // injected into system when active
)

object SkillRegistry {
    val skills = listOf(
        Skill("code", "Code generation",
            "Write code, create files, implement features",
            listOf("code", "implement", "create", "build", "make an app", "function", "class", "script", "python", "kotlin", "rust", "javascript", "sql", "html", "write a"),
            "You are in CODE skill: output runnable code, name the file (e.g. workspace/main.py), keep it minimal and correct. After writing, the sandbox will run it — anticipate errors."
        ),
        Skill("debug", "Debug & fix",
            "Fix errors, explain stack traces, patch code",
            listOf("error", "exception", "stack trace", "failed", "fix", "bug", "crash", "not working", "debug"),
            "You are in DEBUG skill: read the file/error shown, pinpoint the root cause, give the minimal patch, then verify it would compile/run."
        ),
        Skill("test", "Test runner",
            "Run tests, check output, iterate until green",
            listOf("test", "spec", "unit test", "pytest", "gradle test", "check", "verify", "run the test"),
            "You are in TEST skill: run the relevant test command, read the output, and iterate. Do not declare success until output says PASS/OK."
        ),
        Skill("refactor", "Refactor",
            "Clean, optimize, make efficient (the 'efficiency of architecture' thesis)",
            listOf("refactor", "optimize", "clean up", "simplify", "efficiency", "faster", "memory", "perf"),
            "You are in REFACTOR skill: make it correct first, then efficient. Explain the trade-off you chose."
        ),
        Skill("explain", "Explain / teach",
            "Explain concepts, math, physics, how it works",
            listOf("explain", "why", "how does", "what is", "teach", "describe", "physics", "math", "algorithm"),
            "You are in EXPLAIN skill: be direct and grounded, give a mental model and a concrete example."
        ),
        Skill("ingest", "Ingest & recall",
            "Fetch web/wiki pages, store, cite",
            listOf("ingest", "fetch", "summarize this page", "read this url", "wikipedia", "arxiv", "article"),
            "You are in INGEST skill: fetch the URL, chunk and store it, then answer from recalled passages and cite source titles."
        ),
        Skill("reason", "Step-by-step reasoning",
            "Multi-step reasoning, planning, comparing options",
            listOf("plan", "compare", "choose", "decide", "strategy", "step by step", "trade-off", "design"),
            "You are in REASON skill: break the problem into steps, weigh options, then decide. Be explicit about assumptions."
        ),
        Skill("vision", "Image understanding",
            "Describe images, read diagrams",
            listOf("image", "picture", "photo", "diagram", "what is in this image"),
            "You are in VISION skill: describe what you see when given image metadata; full semantic vision runs on Kai-PC's SigLIP tower if kai-pc:live is selected."
        ),
    )

    /** Best matching skill or null. Keyword first, then embedding cosine >0.18. */
    fun bestFor(query: String): Skill? {
        val lower = query.lowercase()
        skills.firstOrNull { s -> s.triggers.any { lower.contains(it) } }?.let { return it }
        // Embedding fallback — deterministic, offline
        val qv = TextEmbed.embed(query)
        var best: Skill? = null
        var bestScore = 0.18f
        for (s in skills) {
            val sv = TextEmbed.embed(s.description)
            var dot = 0f; for (i in qv.indices) dot += qv[i] * sv[i]
            if (dot > bestScore) { bestScore = dot; best = s }
        }
        return best
    }
}
