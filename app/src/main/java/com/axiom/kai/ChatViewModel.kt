package com.axiom.kai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

data class SearchHit(val chatId: String, val chatTitle: String, val msgId: String,
    val role: String, val text: String, val ts: Long)

class ChatViewModel : ViewModel() {
    private fun encText(t:String)= if (AccountManager.isEncryptedMode()) AccountManager.encrypt(t) else t
    private fun decText(t:String)= AccountManager.decrypt(t)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _model = MutableStateFlow("llama3.2:3b")
    val model: StateFlow<String> = _model.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var recursionDepth = 0
    private var lastSessionLog = 0L

    // GGUF download state
    private val _downloadState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val downloadState: StateFlow<Map<String, Boolean>> = _downloadState.asStateFlow()

    fun setModel(m: String) {
        _model.value = m
        // Try to load local GGUF for this model (best effort)
        ModelCatalog.byTag(m)?.let { entry ->
            // check in viewModelScope
        }
    }

    fun refreshDownloadState(ctx: Context) {
        val mgr = ModelManager(ctx)
        _downloadState.value = ModelCatalog.models.associate { it.tag to mgr.isDownloaded(it) }
    }

    /** Launch-time auto-recovery: if current model's GGUF is anywhere on disk, sync + load it (fixes restart-during-download) */
    fun autoLoadIfAvailable(ctx: Context) {
        val mgr = ModelManager(ctx)
        mgr.cleanStaleDownloads() // remove 0-byte stale .part files from interrupted downloads
        val entry = ModelCatalog.byTag(_model.value) ?: return
        if (mgr.isDownloaded(entry)) {
            viewModelScope.launch {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { mgr.autoRecoverAndLoad(entry) }
                if (r == 0) refreshDownloadState(ctx)
            }
        }
    }

    // Download progress state
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    /** Restore progress bars for incomplete downloads after app restart */
    fun resumeIncompleteDownloads(ctx: Context) {
        val mgr = ModelManager(ctx)
        val incomplete = ModelCatalog.models.filter { mgr.isIncomplete(it) }
        for (entry in incomplete) {
            val pct = ((mgr.partialBytes(entry) * 100) / (entry.sizeMb * 1024L * 1024L)).toInt().coerceIn(1, 99)
            _downloadProgress.value = _downloadProgress.value + (entry.tag to pct)
            android.util.Log.i("ChatViewModel", "Resuming incomplete ${entry.tag} at ${pct}%")
            directFallback(ctx, entry) // Range-resume from .part
        }
    }

    fun downloadModel(ctx: Context, tag: String, onEnqueue: (Long) -> Unit = {}) {
        val entry = ModelCatalog.byTag(tag) ?: return
        val mgr = ModelManager(ctx)
        if (mgr.isDownloaded(entry)) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { mgr.loadInRust(entry) }
            refreshDownloadState(ctx)
            return
        }
        // Partial exists (.part)? Prefer resumable direct download (DownloadManager can't resume our .part)
        if (mgr.isIncomplete(entry)) {
            val pct = ((mgr.partialBytes(entry) * 100) / (entry.sizeMb * 1024L * 1024L)).toInt().coerceIn(1, 99)
            _downloadProgress.value = _downloadProgress.value + (entry.tag to pct)
            directFallback(ctx, entry)
            return
        }
        // Try DownloadManager (external dir, SecurityException-safe), then poll+copy+load
        val id = try { mgr.download(entry) } catch (t: Throwable) {
            android.util.Log.e("ChatViewModel", "download() threw: $t")
            -1L
        }
        if (id > 0) {
            onEnqueue(id)
            // Poll DownloadManager in background; on success copy external→internal and load
            viewModelScope.launch {
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                val q = android.app.DownloadManager.Query().setFilterById(id)
                var ticks = 0
                while (ticks < 3600) { // max 2h
                    var sawRow = false
                    dm.query(q)?.use { c ->
                        if (c.moveToFirst()) {
                            sawRow = true
                            val status = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                            if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    mgr.syncExternalToInternal(entry)
                                    mgr.loadInRust(entry)
                                }
                                refreshDownloadState(ctx)
                                return@launch
                            }
                            if (status == android.app.DownloadManager.STATUS_FAILED) {
                                directFallback(ctx, entry)
                                return@launch
                            }
                        }
                    }
                    if (!sawRow) {
                        // Row gone (cleared/notification dismissed) — check file landed anyway
                        if (mgr.isDownloaded(entry)) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                mgr.syncExternalToInternal(entry)
                                mgr.loadInRust(entry)
                            }
                            refreshDownloadState(ctx)
                            return@launch
                        }
                    }
                    ticks++
                    kotlinx.coroutines.delay(2000)
                }
            }
        } else {
            directFallback(ctx, entry)
        }
        // Safety net: if file already on disk (previous session downloaded it), just load it
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            if (mgr.isDownloaded(entry)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (mgr.syncExternalToInternal(entry)) mgr.loadInRust(entry)
                }
                refreshDownloadState(ctx)
            }
        }
    }

    private fun directFallback(ctx: Context, entry: ModelEntry) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ModelManager(ctx).downloadDirect(entry) { pct ->
                    _downloadProgress.value = _downloadProgress.value + (entry.tag to pct)
                }
            }
            if (ok) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ModelManager(ctx).loadInRust(entry)
                }
                _downloadProgress.value = _downloadProgress.value - entry.tag
            }
            refreshDownloadState(ctx)
        }
    }

    fun tryLoadCurrentModel(ctx: Context, onResult: (Int) -> Unit = {}) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val entry = ModelCatalog.byTag(_model.value)
            val r = if (entry == null) -1 else ModelManager(ctx).loadInRust(entry)
            // refresh info after load
            try {
                val info = KaiBridge.lastGgufInfo()
                // info is "path|size|is_gguf|version" — VFE meter will show it via next message
            } catch (_: Exception) {}
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(r) }
        }
    }

    // Cache last GGUF label to avoid spamming JNI on every recomposition
    private var cachedLabel: String? = null
    private var cachedLabelTime: Long = 0
    fun lastGgufLabel(ctx: Context): String {
        // Return cached if <2s old
        if (cachedLabel != null && System.currentTimeMillis() - cachedLabelTime < 2000) return cachedLabel!!
        val label = try {
            val info = KaiBridge.lastGgufInfo() // path|size|is_gguf|version
            val parts = info.split("|")
            if (parts.size >= 4) {
                val file = java.io.File(parts[0])
                val name = file.name
                val mb = parts[1].toLongOrNull()?.let { it/1024/1024 } ?: 0
                val ok = parts[2].toBoolean()
                val ver = parts[3]
                // Re-validate: if Rust says invalid but file exists + >1MB, trust the file
                val actuallyValid = ok || (file.exists() && file.length() > 1024*1024)
                if (actuallyValid) "$name ${mb}MB v$ver ✓" else "$name (0-byte stale file — picker ⬇ to re-download)"
            } else info
        } catch (_: Throwable) {
            val mgr = ModelManager(ctx)
            val dl = try { mgr.downloadedModels().joinToString { it.tag } } catch (_: Throwable) { "" }
            if (dl.isNotEmpty()) "downloaded: $dl (tap picker to load)" else "no GGUF — picker ⬇ 0.5b"
        }
        cachedLabel = label
        cachedLabelTime = System.currentTimeMillis()
        return label
    }

    // Billing — v1 free, v2/v3 $4.99 per period
    fun billingHasV2(ctx: Context): Boolean = BillingManager(ctx).hasV2()
    fun billingHasV3(ctx: Context): Boolean = BillingManager(ctx).hasV3()
    fun billingDaysLeft(ctx: Context, v2: Boolean = true): Long {
        val bm = BillingManager(ctx)
        return if (v2) bm.daysLeftV2() else bm.daysLeftV3()
    }
    fun requiresV2ForModel(tag: String): Boolean {
        // v1 pricing: ALL MODELS free (qwen, llama, gemma). v2 paywall is program features, not models.
        return false
    }

    // ---- Tier 1: chat persistence ----
    private var currentChatId: String = java.util.UUID.randomUUID().toString()
    fun currentChatId(): String = currentChatId
    fun currentModel(): String = _model.value
    private val _chatList = MutableStateFlow<List<ChatEntity>>(emptyList())
    val chatList: StateFlow<List<ChatEntity>> = _chatList.asStateFlow()
    private val _memoryCount = MutableStateFlow(0)
    val memoryCount: StateFlow<Int> = _memoryCount.asStateFlow()

    fun initPersistence(ctx: Context) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val db = KaiDb.get(ctx)
                val chats = db.openHelper.readableDatabase.query("SELECT * FROM chats ORDER BY updatedAt DESC").use { c ->
                    val list = mutableListOf<ChatEntity>()
                    while (c.moveToNext()) {
                        list.add(ChatEntity(
                            id = c.getString(0), title = c.getString(1), model = c.getString(2),
                            createdAt = c.getLong(3), updatedAt = c.getLong(4)))
                    }
                    list
                }
                _chatList.value = chats
                val chat = chats.firstOrNull()
                if (chat != null) {
                    currentChatId = chat.id
                    _model.value = chat.model
                    val msgs = db.messageDao().messagesOnce(chat.id)
                    _messages.value = msgs.map { m ->
                        ChatMessage(id = m.id, role = when (m.role) { "USER" -> Role.USER; "KAI_RECURSIVE" -> Role.KAI_RECURSIVE; else -> Role.KAI },
                            text = decText(m.text), vfe = m.vfe, curvature = m.curvature, temp = m.temp, model = m.model, ts = m.ts, latencyMs = m.latencyMs)
                    }
                } else {
                    createChat(ctx)
                }
                _memoryCount.value = db.memoryDao().count()
            } catch (t: Throwable) {
                android.util.Log.e("ChatViewModel", "initPersistence failed: $t")
            }
        }
    }

    private suspend fun createChat(ctx: Context) {
        val db = KaiDb.get(ctx)
        currentChatId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.chatDao().upsert(ChatEntity(id = currentChatId, title = "New chat", model = _model.value, createdAt = now, updatedAt = now))
        }
        _messages.value = emptyList()
        _chatList.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.chatDao().chats().let { emptyList<ChatEntity>() } // placeholder; UI reads via refresh
        }
        refreshChatList(ctx)
    }

    private suspend fun refreshChatList(ctx: Context) {
        val db = KaiDb.get(ctx)
        val chats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.openHelper.readableDatabase.query("SELECT * FROM chats ORDER BY updatedAt DESC").use { c ->
                val list = mutableListOf<ChatEntity>()
                while (c.moveToNext()) {
                    list.add(ChatEntity(id = c.getString(0), title = c.getString(1), model = c.getString(2),
                        createdAt = c.getLong(3), updatedAt = c.getLong(4)))
                }
                list
            }
        }
        _chatList.value = chats
    }

    fun switchChat(ctx: Context, chatId: String) {
        viewModelScope.launch {
            currentChatId = chatId
            val db = KaiDb.get(ctx)
            val msgs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.messageDao().messagesOnce(chatId) }
            _messages.value = msgs.map { m ->
                ChatMessage(id = m.id, role = when (m.role) { "USER" -> Role.USER; "KAI_RECURSIVE" -> Role.KAI_RECURSIVE; else -> Role.KAI },
                    text = decText(m.text), vfe = m.vfe, curvature = m.curvature, temp = m.temp, model = m.model, ts = m.ts, latencyMs = m.latencyMs)
            }
        }
    }

    /** Search all messages across all chats — for drawer "search your records" */
    suspend fun searchAll(ctx: android.content.Context, q: String): List<SearchHit> {
        if (q.length < 2) return emptyList()
        val db = KaiDb.get(ctx)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val titles = db.chatDao().chatsOnce().associate { it.id to it.title }
            db.messageDao().search(q).map { m ->
                SearchHit(chatId = m.chatId, chatTitle = titles[m.chatId] ?: "chat", msgId = m.id,
                    role = m.role, text = decText(m.text), ts = m.ts)
            }
        }
    }

    fun newChat(ctx: Context) {
        viewModelScope.launch { createChat(ctx) }
    }

    fun deleteChat(ctx: Context, chatId: String) {
        viewModelScope.launch {
            val db = KaiDb.get(ctx)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.chatDao().delete(chatId) }
            if (chatId == currentChatId) createChat(ctx) else refreshChatList(ctx)
        }
    }

    fun clear(ctx: Context) {
        viewModelScope.launch {
            val db = KaiDb.get(ctx)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.messageDao().deleteForChat(currentChatId)
            }
            _messages.value = emptyList()
            recursionDepth = 0
            refreshChatList(ctx)
        }
    }

    // ---- Tier 2: memory API ----
    fun rememberFact(ctx: Context, fact: String) {
        viewModelScope.launch {
            MemoryEngine(ctx).store(fact)
            _memoryCount.value = KaiDb.get(ctx).memoryDao().count()
        }
    }

    fun forgetMemory(ctx: Context, id: String) {
        viewModelScope.launch {
            KaiDb.get(ctx).memoryDao().delete(id)
            _memoryCount.value = KaiDb.get(ctx).memoryDao().count()
        }
    }

    fun setUserName(ctx: Context, n: String) {
        MemoryEngine(ctx).setUserName(n)
        rememberFact(ctx, "user's name is $n")
    }

    fun send(ctx: Context, userText: String) {
        if (userText.isBlank() || _isGenerating.value) return
        val curModel = _model.value
        val db = KaiDb.get(ctx)
        val memEngine = MemoryEngine(ctx)
        val now = System.currentTimeMillis()

        // Persist user message
        val userMsg = ChatMessage(role = Role.USER, text = userText, model = curModel)
        _messages.value = _messages.value + userMsg
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            db.messageDao().insert(MessageEntity(id = userMsg.id, chatId = currentChatId, role = "USER",
                text = userText, vfe = null, curvature = null, temp = null, model = curModel, ts = now))
            db.chatDao().touch(currentChatId, now, userText.take(40))
        }

        _isGenerating.value = true

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Tier 2: extract facts ("remember X", "my name is Y") — ALL DB/memory on IO thread
            try {
                // --- KAI-PC LIVE: if selected model is the encrypted remote, send straight to PC's live opencode session ---
                if (curModel == "kai-pc:live") {
                    // Auto-discover PC if not configured (no manual IP needed)
                    var isConfigured = KaiPcClient.isConfigured(ctx)
                    var host: String? = null
                    var token: String? = null
                    if (!isConfigured) {
                        // Try auto-discovery in background, show "searching" message
                        val searchingMsg = ChatMessage(role = Role.KAI, text = "🔍 Searching for Kai PC on local network/USB…", vfe = 1.0f, curvature = 0.2f, temp = 0.5f, model = "kai-pc:live")
                        _messages.value = _messages.value + searchingMsg
                        host = KaiPcClient.autoDiscover(ctx)
                        // Remove searching message
                        _messages.value = _messages.value.filter { it.id != searchingMsg.id }
                        if (host != null) {
                            isConfigured = true
                            token = KaiPcClient.getToken(ctx)
                        }
                    }
                    if (!isConfigured || host == null) {
                        val msg = "🔒 Kai PC not found automatically.\n\n" +
                            "On PC: `python3 tools/kai_pc_server.py --port 8443 --token kai-secret-123`\n" +
                            "On phone: ensure WiFi same as PC, or USB: `adb forward tcp:8443 tcp:8443` and use 127.0.0.1:8443\n" +
                            "Then: Terminal → ⚙️ → enter PC IP:port + token, or just say 'export history to phone' on PC and 'import' here."
                        val toolMsg = ChatMessage(role = Role.KAI, text = msg, vfe = 1.0f, curvature = 0.2f, temp = 0.5f, model = "kai-pc:live")
                        _messages.value = _messages.value + toolMsg
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            db.messageDao().insert(MessageEntity(id = toolMsg.id, chatId = currentChatId, role = "KAI",
                                text = msg, vfe = 1.0f, curvature = 0.2f, temp = 0.5f, model = "kai-pc:live", ts = System.currentTimeMillis()))
                        }
                        _isGenerating.value = false
                        return@launch
                    }
                    // Create placeholder for PC reply (will stream into it when PC responds)
                    val pcId = java.util.UUID.randomUUID().toString()
                    val pcMsg = ChatMessage(id = pcId, role = Role.KAI, text = "↗ Sending to Kai PC (${host})…", vfe = 2.5f, curvature = 0.5f, temp = 0.9f, model = "kai-pc:live")
                    _messages.value = _messages.value + pcMsg
                    // Send to PC (encrypted)
                    val result = KaiPcClient.send(ctx, userText, "text", null)
                    val reply = result.getOrElse { "⚠ PC error: ${it.message}\nCheck PC is running: python3 tools/kai_pc_server.py --port 8443" }
                    _messages.value = _messages.value.map { if (it.id == pcId) it.copy(text = "🖥️ Kai PC live:\n$reply") else it }
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        db.messageDao().insert(MessageEntity(id = pcId, chatId = currentChatId, role = "KAI",
                            text = "🖥️ Kai PC live:\n$reply", vfe = 2.5f, curvature = 0.5f, temp = 0.9f, model = "kai-pc:live", ts = System.currentTimeMillis()))
                    }
                    _isGenerating.value = false
                    return@launch
                }

                memEngine.extractFact(userText)?.let { fact ->
                    memEngine.store(fact)
                    _memoryCount.value = db.memoryDao().count()
                }
                if (userText.lowercase().startsWith("my name is ")) {
                    memEngine.setUserName(userText.substring(11).trim().split(" ").first())
                }

                // Tier 3: recall top-k memories for this query → inject into Kai's prompt
                val memBlock = memEngine.contextBlock(userText)
                // Tools + natural language import/export (agent-recognizable, no manual file pick)
                val lowerNl = userText.lowercase()
                // Natural language export/import without needing exact command
                if ((lowerNl.contains("export") && (lowerNl.contains("phone") || lowerNl.contains("history") || lowerNl.contains("download"))) ||
                    lowerNl.contains("send to phone") || lowerNl.contains("transfer to phone")) {
                    val f = java.io.File(ctx.getExternalFilesDir(null), "kai_state_export.json")
                    val path = MemoryDbKtDarwinSyncExport(f.absolutePath, ctx)
                    val msg = "✓ Exported Kai state to: $path\n  Pull to desktop via USB: adb pull $path ~/Downloads/\n  Or find it in Download/ on this phone."
                    val toolMsg = ChatMessage(role = Role.KAI, text = msg, vfe = 1.0f, curvature = 0.2f, temp = 0.5f, model = "tool")
                    _messages.value = _messages.value + toolMsg
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        db.messageDao().insert(MessageEntity(id = toolMsg.id, chatId = currentChatId, role = "KAI",
                            text = msg, vfe = 1.0f, curvature = 0.2f, temp = 0.5f, model = "tool", ts = System.currentTimeMillis()))
                    }
                    _isGenerating.value = false
                } else if ((lowerNl.contains("import") && (lowerNl.contains("phone") || lowerNl.contains("download") || lowerNl.contains("history") || lowerNl.contains("opencode"))) ||
                    lowerNl.contains("sync from download") || lowerNl.contains("load from download")) {
                    val count = scanAndImportDownloads(ctx)
                    // Also try to auto-recover GGUF if present
                    val mgr = ModelManager(ctx)
                    val dlModels = mgr.downloadedModels()
                    val msg = if (count > 0) "✓ Imported $count memories/chats from Download/. Also found ${dlModels.size} GGUF(s) on disk."
                              else "No kai_state/session files found in Download/. Drop a kai_state_export.json or session-*.md there and say 'import' again. Found ${dlModels.size} GGUF(s) already."
                    val toolMsg = ChatMessage(role = Role.KAI, text = msg, vfe = 1.0f, curvature = 0.2f, temp = 0.5f, model = "tool")
                    _messages.value = _messages.value + toolMsg
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        db.messageDao().insert(MessageEntity(id = toolMsg.id, chatId = currentChatId, role = "KAI",
                            text = msg, vfe = 1.0f, curvature = 0.2f, temp = 0.5f, model = "tool", ts = System.currentTimeMillis()))
                    }
                    _isGenerating.value = false
                } else {
                    val toolResult = Tools.tryTool(ctx, userText)
                    if (toolResult != null) {
                        val toolMsg = ChatMessage(role = Role.KAI, text = toolResult, vfe = 1.0f, curvature = 0.2f, temp = 0.5f, model = "tool")
                        _messages.value = _messages.value + toolMsg
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            db.messageDao().insert(MessageEntity(id = toolMsg.id, chatId = currentChatId, role = "KAI",
                                text = toolResult, vfe = 1.0f, curvature = 0.2f, temp = 0.5f, model = "tool", ts = System.currentTimeMillis()))
                        }
                        _isGenerating.value = false
                    } else {
                        continueSendAfterTools(ctx, db, memEngine, userText, curModel)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("ChatViewModel", "send pipeline failed: $t")
                _isGenerating.value = false
            }
        }
    }

    private fun continueSendAfterTools(ctx: Context, db: KaiDb, memEngine: MemoryEngine, userText: String, curModel: String) {
        val t0 = System.currentTimeMillis()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Gemini remote models — after Google login, free
                if (curModel.startsWith("gemini:")) {
                    if (!GeminiClient.isLoggedIn(ctx)) {
                        val msg = "⚠ Please sign in with Google first to use Gemini.\nTap ✦ → Sign in with Google, then paste your free API key from aistudio.google.com → Gemini models unlock."
                        val m = ChatMessage(role = Role.KAI, text = msg, model = curModel, vfe = 1f, curvature = 0.2f, temp = 0.7f)
                        _messages.value = _messages.value + m
                        db.messageDao().insert(MessageEntity(id = m.id, chatId = currentChatId, role = "KAI", text = encText(msg), vfe = 1f, curvature = 0.2f, temp = 0.7f, model = curModel, ts = System.currentTimeMillis()))
                        _isGenerating.value = false
                        return@launch
                    }
                    if (!GeminiClient.hasApiKey(ctx)) {
                        val msg = "⚠ Gemini API key not set.\nGet a free key at aistudio.google.com → paste it in ✦ → Gemini API key."
                        val m = ChatMessage(role = Role.KAI, text = msg, model = curModel, vfe = 1f, curvature = 0.2f, temp = 0.7f)
                        _messages.value = _messages.value + m
                        db.messageDao().insert(MessageEntity(id = m.id, chatId = currentChatId, role = "KAI", text = encText(msg), vfe = 1f, curvature = 0.2f, temp = 0.7f, model = curModel, ts = System.currentTimeMillis()))
                        _isGenerating.value = false
                        return@launch
                    }
                    val soulBlockTmp = Soul.build(ctx, memEngine, curModel)
                    val memTmp = memEngine.contextBlock(userText)
                    val knowTmp = Knowledge.contextBlock(ctx, userText)
                    val histTmp = db.messageDao().messagesOnce(currentChatId).takeLast(12).filter { it.role == "USER" || it.role == "KAI" }
                    val arrTmp = org.json.JSONArray()
                    arrTmp.put(org.json.JSONObject().put("role", "system").put("content", soulBlockTmp + knowTmp + memTmp + Tools.toolsPrompt(ctx)))
                    for (h in histTmp) arrTmp.put(org.json.JSONObject().put("role", if (h.role == "USER") "user" else "assistant").put("content", decText(h.text).take(1500)))
                    arrTmp.put(org.json.JSONObject().put("role", "user").put("content", userText))
                    val gemModel = if (curModel == "gemini:pro") "gemini-1.5-pro" else "gemini-1.5-flash"
                    val out = GeminiClient.generate(ctx, arrTmp.toString(), gemModel)
                    val elapsed = System.currentTimeMillis() - t0
                    val kaiId = java.util.UUID.randomUUID().toString()
                    val kaiMsg = ChatMessage(id = kaiId, role = Role.KAI, text = out, model = curModel, vfe = 1f, curvature = 0.2f, temp = 0.7f, latencyMs = elapsed)
                    _messages.value = _messages.value + kaiMsg
                    db.messageDao().insert(MessageEntity(id = kaiId, chatId = currentChatId, role = "KAI", text = encText(out), vfe = 1f, curvature = 0.2f, temp = 0.7f, model = curModel, ts = System.currentTimeMillis(), latencyMs = elapsed))
                    _isGenerating.value = false
                    return@launch
                }
                // Other remote LLMs — DeepSeek / GPT / Qwen / Claude (API key gated, via RemoteLLMClient)
                if (curModel.startsWith("deepseek:") || curModel.startsWith("gpt:") || curModel.startsWith("qwen:") && curModel !in listOf("qwen2.5:0.5b", "qwen2.5-coder:3b", "qwen2.5:7b") || curModel.startsWith("claude:")) {
                    val soulTmp = Soul.build(ctx, memEngine, curModel)
                    val memTmp2 = memEngine.contextBlock(userText)
                    val knowTmp2 = Knowledge.contextBlock(ctx, userText)
                    val hist2 = db.messageDao().messagesOnce(currentChatId).takeLast(12).filter { it.role == "USER" || it.role == "KAI" }
                    val arr2 = org.json.JSONArray()
                    arr2.put(org.json.JSONObject().put("role", "system").put("content", soulTmp + knowTmp2 + memTmp2 + Tools.toolsPrompt(ctx)))
                    for (h in hist2) arr2.put(org.json.JSONObject().put("role", if (h.role == "USER") "user" else "assistant").put("content", decText(h.text).take(1500)))
                    arr2.put(org.json.JSONObject().put("role", "user").put("content", userText))
                    val out2 = RemoteLLMClient.generate(ctx, arr2.toString(), curModel)
                    val elapsed2 = System.currentTimeMillis() - t0
                    val kaiId2 = java.util.UUID.randomUUID().toString()
                    val kaiMsg2 = ChatMessage(id = kaiId2, role = Role.KAI, text = out2, model = curModel, vfe = 1f, curvature = 0.2f, temp = 0.7f, latencyMs = elapsed2)
                    _messages.value = _messages.value + kaiMsg2
                    db.messageDao().insert(MessageEntity(id = kaiId2, chatId = currentChatId, role = "KAI", text = encText(out2), vfe = 1f, curvature = 0.2f, temp = 0.7f, model = curModel, ts = System.currentTimeMillis(), latencyMs = elapsed2))
                    _isGenerating.value = false
                    return@launch
                }

                val memBlock = memEngine.contextBlock(userText)
                val toolsBlock = Tools.toolsPrompt(ctx)
                // Skill (Block B) — extra prompt when a skill matches
                val skill = SkillRegistry.bestFor(userText)
                val skillBlock = skill?.let { "\n[Skill:${it.id}:${it.name}] ${it.extraPrompt}\n\n" } ?: ""

                // ---- SOUL + ROUTER + HISTORY (Blocks A/C/E) ----
                // Router: "auto" picks the right voice (code/deep/fast) per task
                var genTag = curModel
                var genSlot = 0
                if (curModel == "auto") {
                    val routed = ModelRouter.route(userText, ModelManager(ctx))
                    if (routed != null) {
                        genTag = routed.first; genSlot = routed.second
                        val entry = ModelCatalog.byTag(genTag)
                        if (entry != null && !ModelManager(ctx).isLoadedInSlot(entry, genSlot)) {
                            ModelManager(ctx).loadInRust(entry, genSlot)
                        }
                    }
                }

                // Soul: identity + live state + session memory (always the system message)
                val soulBlock = Soul.build(ctx, memEngine, genTag)
                val knowledgeBlock = Knowledge.contextBlock(ctx, userText)

                // History: last 12 turns from this chat — the model reads what came before
                val historyMsgs = db.messageDao().messagesOnce(currentChatId).takeLast(12)
                    .filter { it.role == "USER" || it.role == "KAI" }

                val curvature = estimateCurvature(userText)
                val surprise = estimateSurprise(userText, curvature)
                val kl = estimateKL(userText)
                val vfe = safeVFE(surprise, kl)
                val baseTemp = 0.85f
                val temp = safeTemp(baseTemp, curvature, 0.4f)

                // Build the chat JSON: [soul+tools+memory+knowledge system] + history + current turn
                val chatArr = org.json.JSONArray()
                val sysObj = org.json.JSONObject()
                sysObj.put("role", "system")
                sysObj.put("content", soulBlock + skillBlock + "\n\n" + knowledgeBlock + memBlock + toolsBlock)
                chatArr.put(sysObj)
                for (h in historyMsgs) {
                    val o = org.json.JSONObject()
                    o.put("role", if (h.role == "USER") "user" else "assistant")
                    o.put("content", if (decText(h.text).length > 1500) decText(h.text).take(1500) + "…" else h.text)
                    chatArr.put(o)
                }
                val userObj = org.json.JSONObject()
                userObj.put("role", "user")
                userObj.put("content", userText)
                chatArr.put(userObj)
                val historyJson = chatArr.toString()

                val kaiId = java.util.UUID.randomUUID().toString()
                val kaiMsg = ChatMessage(id = kaiId, role = Role.KAI, text = "", vfe = vfe, curvature = curvature, temp = temp,
                    model = if (curModel == "auto") "auto→$genTag" else curModel)
                _messages.value = _messages.value + kaiMsg

                // Block G — Agent loop for implementation tasks (decompose→code→write→shell→fix)
                android.util.Log.i("AgentLoop", "check isAgentTask for: " + userText.take(60) + " -> " + AgentLoop.isAgentTask(userText).toString())
                if (AgentLoop.isAgentTask(userText)) {
                    val agentText = AgentLoop.run(ctx, historyMsgs, soulBlock + skillBlock, toolsBlock, memBlock, knowledgeBlock,
                        userText, genTag, genSlot, temp, vfe) { step ->
                        _messages.value = _messages.value.map { if (it.id == kaiId) it.copy(text = it.text + step) else it }
                    }
                    val elapsedAgent = System.currentTimeMillis() - t0
                    val finalAgent = _messages.value.find { it.id == kaiId }?.text?.takeIf { it.isNotBlank() } ?: agentText
                    _messages.value = _messages.value.map { if (it.id == kaiId) it.copy(latencyMs = elapsedAgent) else it }
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        db.messageDao().insert(MessageEntity(id = kaiId, chatId = currentChatId, role = "KAI",
                            text = encText(finalAgent), vfe = vfe, curvature = curvature, temp = temp,
                            model = if (curModel == "auto") "auto→$genTag" else curModel,
                            ts = System.currentTimeMillis(), latencyMs = elapsedAgent))
                        if (System.currentTimeMillis() - lastSessionLog > 60_000L) {
                            lastSessionLog = System.currentTimeMillis()
                            val title = db.chatDao().chat(currentChatId)?.title ?: "chat"
                            SessionLog.append(ctx, title, historyMsgs.size / 2 + 1, userText)
                        }
                    }
                    _isGenerating.value = false
                    return@launch
                }

                android.util.Log.i("KaiChat", "historyJson len=" + historyJson.length + " slot=" + genSlot + " model=" + genTag)
                val streamer = StreamingGenerator(ctx)
                streamer.streamChat(historyJson, genSlot, temp, vfe,
                    onToken = { tok ->
                        // Batch updates slightly to avoid recomposition storm on long answers (freeze fix)
                        _messages.value = _messages.value.map { if (it.id == kaiId) it.copy(text = it.text + tok) else it }
                    },
                    onDone = {
                        val finalText = _messages.value.find { it.id == kaiId }?.text ?: ""
                        val elapsed = System.currentTimeMillis() - t0
                        _messages.value = _messages.value.map { if (it.id == kaiId) it.copy(latencyMs = elapsed) else it }
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            db.messageDao().insert(MessageEntity(id = kaiId, chatId = currentChatId, role = "KAI",
                                text = encText(finalText), vfe = vfe, curvature = curvature, temp = temp,
                                model = if (curModel == "auto") "auto→$genTag" else curModel,
                                ts = System.currentTimeMillis(), latencyMs = elapsed))
                            // Permanent agent log (throttled to 1/60s)
                            if (System.currentTimeMillis() - lastSessionLog > 60_000L) {
                                lastSessionLog = System.currentTimeMillis()
                                val title = db.chatDao().chat(currentChatId)?.title ?: "chat"
                                SessionLog.append(ctx, title, historyMsgs.size / 2 + 1, userText)
                            }
                        }
                        if (vfe > 3.0f && recursionDepth < 2) {
                            recursionDepth++
                            val selfPrompt = "What would reduce VFE about: \"$userText\"? (curvature $curvature)"
                            val selfVfe = safeVFE(surprise * 0.7f, kl * 0.8f)
                            val selfTemp = safeTemp(temp, curvature * 0.8f, 0.35f)
                            val ghostId = java.util.UUID.randomUUID().toString()
                            val ghostMsg = ChatMessage(id = ghostId, role = Role.KAI_RECURSIVE, text = "", vfe = selfVfe, curvature = curvature*0.8f, temp = selfTemp, model = curModel)
                            _messages.value = _messages.value + ghostMsg
                            viewModelScope.launch {
                                streamer.stream(selfPrompt, selfTemp, selfVfe,
                                    onToken = { t2 ->
                                        _messages.value = _messages.value.map { if (it.id == ghostId) it.copy(text = if (it.text.isEmpty()) "↻ $t2" else it.text + t2) else it }
                                    },
                                    onDone = {
                                        val ghostText = _messages.value.find { it.id == ghostId }?.text ?: ""
                                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            db.messageDao().insert(MessageEntity(id = ghostId, chatId = currentChatId, role = "KAI_RECURSIVE",
                                                text = encText(ghostText), vfe = selfVfe, curvature = curvature*0.8f, temp = selfTemp,
                                                model = curModel, ts = System.currentTimeMillis()))
                                        }
                                        _isGenerating.value = false
                                    }
                                )
                            }
                        } else {
                            recursionDepth = 0
                            _isGenerating.value = false
                        }
                    }
                )
            } catch (t: Throwable) {
                android.util.Log.e("ChatViewModel", "send pipeline failed: $t")
                _isGenerating.value = false
            }
        }
    }

    fun promoteRecursive(ctx: Context, msg: ChatMessage) {
        val stripped = msg.text.removePrefix("↻ ").take(400)
        send(ctx, stripped)
    }

    // --- Kai PC live: send image/file directly to PC's live terminal ---
    fun sendPCImage(ctx: Context, base64: String, filename: String) {
        if (_isGenerating.value) return
        val userText = "📷 Image $filename"
        val userMsg = ChatMessage(role = Role.USER, text = userText, model = "kai-pc:live")
        _messages.value = _messages.value + userMsg
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val db = KaiDb.get(ctx)
            db.messageDao().insert(MessageEntity(id = userMsg.id, chatId = currentChatId, role = "USER", text = encText(userText), vfe = null, curvature = null, temp = null, model = "kai-pc:live", ts = System.currentTimeMillis()))
        }
        _isGenerating.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val result = KaiPcClient.send(ctx, base64, "image", filename)
                val reply = result.getOrElse { "⚠ PC error: ${it.message}" }
                val msg = ChatMessage(role = Role.KAI, text = "🖥️ Kai PC (image $filename):\n$reply", vfe = 2.5f, curvature = 0.6f, temp = 0.9f, model = "kai-pc:live")
                _messages.value = _messages.value + msg
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    KaiDb.get(ctx).messageDao().insert(MessageEntity(id = msg.id, chatId = currentChatId, role = "KAI", text = msg.text, vfe = 2.5f, curvature = 0.6f, temp = 0.9f, model = "kai-pc:live", ts = System.currentTimeMillis()))
                }
            } catch (t: Throwable) {
                android.util.Log.e("ChatViewModel", "pc image send failed: $t")
            }
            _isGenerating.value = false
        }
    }

    fun sendPCFile(ctx: Context, base64: String, filename: String) {
        if (_isGenerating.value) return
        val userText = "📎 File $filename"
        val userMsg = ChatMessage(role = Role.USER, text = userText, model = "kai-pc:live")
        _messages.value = _messages.value + userMsg
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            KaiDb.get(ctx).messageDao().insert(MessageEntity(id = userMsg.id, chatId = currentChatId, role = "USER", text = encText(userText), vfe = null, curvature = null, temp = null, model = "kai-pc:live", ts = System.currentTimeMillis()))
        }
        _isGenerating.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val result = KaiPcClient.send(ctx, base64, "file", filename)
                val reply = result.getOrElse { "⚠ PC error: ${it.message}" }
                val msg = ChatMessage(role = Role.KAI, text = "🖥️ Kai PC (file $filename):\n$reply", vfe = 2.5f, curvature = 0.6f, temp = 0.9f, model = "kai-pc:live")
                _messages.value = _messages.value + msg
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    KaiDb.get(ctx).messageDao().insert(MessageEntity(id = msg.id, chatId = currentChatId, role = "KAI", text = msg.text, vfe = 2.5f, curvature = 0.6f, temp = 0.9f, model = "kai-pc:live", ts = System.currentTimeMillis()))
                }
            } catch (t: Throwable) {
                android.util.Log.e("ChatViewModel", "pc file send failed: $t")
            }
            _isGenerating.value = false
        }
    }

    fun clear() { _messages.value = emptyList(); recursionDepth = 0 }

    // ---- stubs that become real kai-fusion later ----

    private fun estimateCurvature(text: String): Float {
        // hash distance vs last 3 messages → 0..1
        val last = _messages.value.takeLast(3).joinToString(" ") { it.text.take(20) }
        if (last.isEmpty()) return 0.45f
        val h = abs(text.hashCode() - last.hashCode()) % 1000 / 1000f
        return (0.2f + h * 0.6f).coerceIn(0f, 1f)
    }
    private fun estimateSurprise(text: String, c: Float): Float {
        // length + curvature
        return (text.length / 120f + c * 1.5f + 0.5f).coerceIn(0.5f, 5f)
    }
    private fun estimateKL(text: String): Float {
        // stub: distance to attractor prior (later 173 vectors)
        return (abs(text.hashCode() % 100) / 100f * 0.8f + 0.2f)
    }
    private fun safeVFE(s: Float, k: Float): Float = try { KaiBridge.calculateVFE(s,k) } catch (_: Throwable) { KaiBridge.calculateVFEFallback(s,k) }
    private fun safeTemp(t: Float, c: Float, a: Float): Float = try { KaiBridge.curvatureToTemp(t,c,a) } catch (_: Throwable) { KaiBridge.curvatureToTempFallback(t,c,a) }
    private fun safeGenerate(p: String, t: Float, v: Float): String = try { KaiBridge.generate(p,t,v) } catch (_: Throwable) {
        // Kotlin fallback template
        if (v > 3) "[Kai VFE ${"%.1f".format(v)} T${"%.2f".format(t)}] \"$p\" — novel. What prior minimizes KL here? (VFE→${"%.1f".format(v*0.8)})"
        else "[Kai VFE ${"%.1f".format(v)}] \"$p\" — in groove. Next: assert_eq!(kai_calculate_vfe(s,kl), s+kl)"
    }
}
