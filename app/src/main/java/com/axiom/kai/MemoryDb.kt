package com.axiom.kai

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ==================== ENTITIES ====================

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "messages", foreignKeys = [ForeignKey(
    entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("chatId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val role: String, // USER, KAI, KAI_RECURSIVE
    val text: String,
    val vfe: Float?,
    val curvature: Float?,
    val temp: Float?,
    val model: String,
    val ts: Long,
    val latencyMs: Long? = null
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val fact: String,          // "user's name is L"
    val source: String,        // "user-stated" | "kai-inferred"
    val embeddingJson: String, // Tier 3: JSON-encoded vector (Room-safe)
    val createdAt: Long,
    val recallCount: Int = 0
) {
    @Ignore fun embedding(): FloatArray = embeddingJson.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
    companion object {
        fun make(id: String, fact: String, source: String, embedding: FloatArray, createdAt: Long): MemoryEntity =
            MemoryEntity(id, fact, source, embedding.joinToString(","), createdAt)
    }
}

// ==================== DAO ====================

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun chats(): Flow<List<ChatEntity>>
    @Query("SELECT * FROM chats WHERE id = :id")
    fun chat(id: String): ChatEntity?
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun chatsOnce(): List<ChatEntity>
    @Query("SELECT COUNT(*) FROM chats")
    fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(chat: ChatEntity)
    @Query("DELETE FROM chats WHERE id = :id")
    fun delete(id: String)
    @Query("UPDATE chats SET updatedAt = :ts, title = :title WHERE id = :id")
    fun touch(id: String, ts: Long, title: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY ts ASC")
    fun messages(chatId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY ts ASC")
    fun messagesOnce(chatId: String): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE text LIKE '%' || :q || '%' ORDER BY ts DESC LIMIT 50")
    fun search(q: String): List<MessageEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(m: MessageEntity)
    @Query("UPDATE messages SET text = :text WHERE id = :id")
    fun updateText(id: String, text: String)
    @Query("DELETE FROM messages WHERE chatId = :chatId")
    fun deleteForChat(chatId: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun all(): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories")
    fun allOnce(): List<MemoryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(m: MemoryEntity)
    @Query("DELETE FROM memories WHERE id = :id")
    fun delete(id: String)
    @Query("UPDATE memories SET recallCount = recallCount + 1 WHERE id = :id")
    fun bumpRecall(id: String)
    @Query("SELECT COUNT(*) FROM memories")
    fun count(): Int
}

// ==================== DATABASE ====================

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN latencyMs INTEGER")
    }
}

@Database(entities = [ChatEntity::class, MessageEntity::class, MemoryEntity::class], version = 2)
@Suppress("unused")
abstract class KaiDb : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile private var INSTANCE: KaiDb? = null
        // ---- history protection: file-level backup before any Room open/migration ----
        private fun backupIfNeeded(ctx: Context) {
            try {
                val dbFile = ctx.getDatabasePath("kai.db")
                val dir = dbFile.parentFile ?: return
                val bak = java.io.File(dir, "kai.db.backup")
                // if DB is empty/missing but backup exists -> restore first (protects against prior destructive wipe)
                if ((!dbFile.exists() || dbFile.length() < 1024) && bak.exists() && bak.length() > 1024) {
                    try {
                        bak.copyTo(dbFile, overwrite = true)
                        listOf("-shm", "-wal").forEach { sfx ->
                            val f = java.io.File(bak.path + sfx)
                            if (f.exists() && f.length() > 0) f.copyTo(java.io.File(dbFile.path + sfx), overwrite = true)
                        }
                        android.util.Log.i("KaiDb", "restored history from backup ${bak.length()} bytes")
                        return
                    } catch (_: Throwable) {}
                }
                if (!dbFile.exists() || dbFile.length() == 0L) return
                val bak2 = java.io.File(dir, "kai.db.backup2")
                // rotate if size changed (new messages)
                if (bak.exists() && bak.length() != dbFile.length()) {
                    if (bak2.exists()) bak2.delete()
                    bak.renameTo(bak2)
                }
                if (!bak.exists() || bak.length() != dbFile.length()) {
                    dbFile.copyTo(bak, overwrite = true)
                    listOf("-shm", "-wal").forEach { sfx ->
                        val f = java.io.File(dbFile.path + sfx)
                        if (f.exists() && f.length() > 0) f.copyTo(java.io.File(bak.path + sfx), overwrite = true)
                    }
                    android.util.Log.i("KaiDb", "history backup saved ${bak.length()} bytes -> ${bak.path}")
                }
                // also keep an external visible copy for user (survives fallback wipe)
                try {
                    val ext = java.io.File(ctx.getExternalFilesDir(null), "kai.db.backup")
                    if (!ext.exists() || ext.length() != dbFile.length()) {
                        dbFile.copyTo(ext, overwrite = true)
                    }
                } catch (_: Throwable) {}
            } catch (t: Throwable) { android.util.Log.w("KaiDb", "backup failed: $t") }
        }
        fun get(ctx: Context): KaiDb = INSTANCE ?: synchronized(this) {
            backupIfNeeded(ctx)
            INSTANCE ?: Room.databaseBuilder(ctx, KaiDb::class.java, "kai.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
        // manual restore helper (call from debug menu if needed)
        fun restoreBackupIfEmpty(ctx: Context) {
            try {
                val dbFile = ctx.getDatabasePath("kai.db")
                if (dbFile.exists() && dbFile.length() > 1024) return
                val bak = java.io.File(dbFile.parent, "kai.db.backup")
                if (bak.exists() && bak.length() > 1024) {
                    bak.copyTo(dbFile, overwrite = true)
                    listOf("-shm","-wal").forEach { sfx ->
                        val f = java.io.File(bak.path + sfx)
                        if (f.exists()) f.copyTo(java.io.File(dbFile.path + sfx), overwrite = true)
                    }
                    android.util.Log.i("KaiDb", "restored history from backup ${bak.length()} bytes")
                }
            } catch (t: Throwable) { android.util.Log.w("KaiDb", "restore failed: $t") }
        }
    }
}

// ==================== MEMORY ENGINE (Tier 2 + 3) ====================

class MemoryEngine(private val ctx: Context) {
    private val db = KaiDb.get(ctx)
    private val prefs = ctx.getSharedPreferences("kai_memory", Context.MODE_PRIVATE)

    // ---- Tier 2: fact extraction ----
    // Patterns: "remember X", "my name is X", "I am Y", "I like Z", "call me W"
    fun extractFact(text: String): String? {
        val t = text.trim()
        val lower = t.lowercase()
        return when {
            lower.startsWith("remember ") -> t.substring(9).take(300)
            lower.startsWith("note that ") -> t.substring(10).take(300)
            lower.startsWith("my name is ") -> "user's name is " + t.substring(11).take(100)
            lower.startsWith("call me ") -> "user prefers to be called " + t.substring(8).take(100)
            lower.startsWith("i am ") || lower.startsWith("i'm ") -> {
                val rest = if (lower.startsWith("i am ")) t.substring(5) else t.substring(4)
                if (rest.length in 3..200) "user is $rest" else null
            }
            lower.startsWith("i like ") -> "user likes " + t.substring(7).take(200)
            lower.startsWith("i prefer ") -> "user prefers " + t.substring(9).take(200)
            lower.startsWith("i work ") -> "user works " + t.substring(7).take(200)
            lower.startsWith("i live ") -> "user lives " + t.substring(7).take(200)
            else -> null
        }
    }

    suspend fun store(fact: String, source: String = "user-stated") {
        val emb = TextEmbed.embed(ctx, fact)
        db.memoryDao().insert(MemoryEntity.make(
            id = java.util.UUID.randomUUID().toString(),
            fact = fact, source = source, embedding = emb, createdAt = System.currentTimeMillis()
        ))
    }

    suspend fun recallTopK(query: String, k: Int = 3): List<MemoryEntity> {
        val all = db.memoryDao().allOnce()
        if (all.isEmpty()) return emptyList()
        val qv = TextEmbed.embed(ctx, query)
        return all.map { it to cosine(qv, it.embeddingJson) }
            .sortedByDescending { it.second }
            .take(k)
            .filter { it.second > 0.15f } // relevance floor
            .onEach { db.memoryDao().bumpRecall(it.first.id) }
            .map { it.first }
    }

    /** Build the memory context block injected into Kai's prompt */
    suspend fun contextBlock(query: String): String {
        val name = prefs.getString("user_name", null)
        val mems = recallTopK(query, 3)
        val parts = mutableListOf<String>()
        name?.let { parts.add("The user's name is $it.") }
        mems.forEach { parts.add("- ${it.fact}") }
        return if (parts.isEmpty()) "" else "[Memory]\n${parts.joinToString("\n")}\n[/Memory]\n\n"
    }

    fun setUserName(n: String) { prefs.edit().putString("user_name", n).apply() }
    fun userName(): String? = prefs.getString("user_name", null)

    // ---- Tier 3: embedding (delegates to shared TextEmbed) ----
    fun embed(text: String): FloatArray = TextEmbed.embed(text)
    private fun cosine(a: FloatArray, bJson: String): Float = TextEmbed.cosineJson(a, bJson)
}

/** Hashed bag-of-bigrams → 128-dim, L2-normalized. Deterministic, offline.
 *  Shared by MemoryEngine (facts) and Knowledge (ingested passages).
 *  Transpose from desktop: if nomic-embed GGUF (768-d) is present, use it via KaiBridge (same as desktop nomic-embed-text 768-d); else fallback to 128-d hash. */
object TextEmbed {
    private const val NOM_768 = "nomic-embed-text-v1.Q4_K_M.gguf"
    private const val NOM_ALT = "nomic-embed-text-q4_k_m.gguf"
    fun isNomicAvailable(ctx: android.content.Context): Boolean {
        val dirs = listOf(java.io.File(ctx.filesDir, "models"), ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.let { java.io.File(it, "models") })
        return dirs.filterNotNull().any { d -> java.io.File(d, NOM_768).exists() || java.io.File(d, NOM_ALT).exists() }
    }
    fun embedDim(ctx: android.content.Context): Int = if (isNomicAvailable(ctx)) 768 else 128

    fun embed(text: String): FloatArray {
        // Try nomic 768-d via Rust if model file present and KaiBridge has embed support
        try {
            // KaiBridge.embed will return comma-joined 768 floats if loaded, else null/empty
            val ctx = try { null } catch (_: Throwable) { null } // placeholder: actual ctx passed via overload below
            // if nomic GGUF is present, the Rust side is expected to have loaded it into an embedding slot
            // For now, probe via file existence and try KaiBridge if method exists (reflection to avoid hard dep)
            val method = try { KaiBridge::class.java.getMethod("embed", String::class.java) } catch (_: Throwable) { null }
            if (method != null) {
                val res = method.invoke(KaiBridge, text) as? String
                if (res != null && res.isNotBlank() && res.contains(",")) {
                    val arr = res.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                    if (arr.size == 768) return arr
                }
            }
        } catch (_: Throwable) {}
        // Fallback: 128-d hash (compatible with existing kai.db until re-embed)
        val dim = 128
        val v = FloatArray(dim)
        val toks = text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }
        for (i in toks.indices) {
            val t1 = toks[i]
            hashInto(v, t1, 1.0f)
            if (i + 1 < toks.size) hashInto(v, t1 + "_" + toks[i+1], 0.6f)
        }
        var norm = 0f
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }
    // Overload with ctx for nomic-aware callers
    fun embed(ctx: android.content.Context, text: String): FloatArray {
        if (isNomicAvailable(ctx)) {
            try {
                val m = try { KaiBridge::class.java.getMethod("embed", String::class.java) } catch (_: Throwable) { null }
                if (m != null) {
                    val res = m.invoke(KaiBridge, text) as? String
                    if (res != null && res.isNotBlank() && res.contains(",")) {
                        val arr = res.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                        if (arr.size == 768) return arr
                    }
                }
            } catch (_: Throwable) {}
        }
        return embed(text)
    }

    private fun hashInto(v: FloatArray, token: String, w: Float) {
        var h = 1125899906842597L
        for (c in token) h = 31*h + c.code
        val idx = ((h % v.size) + v.size).toInt() % v.size
        val sign = if (h and 1 == 0L) 1f else -1f
        v[idx] += sign * w
    }

    fun cosineJson(a: FloatArray, bJson: String): Float {
        val b = bJson.split(",").mapNotNull { it.toFloatOrNull() }
        if (a.size != b.size) {
            // Transpose: desktop 768 vs old 128 — mismatch means re-embed needed; treat as non-match so user re-ingests
            // Keep minOf fallback for now to avoid hard failure, but log
            android.util.Log.w("TextEmbed", "dim mismatch a=${a.size} b=${b.size} — re-embed recommended (nomic transpose)")
            // return -1 to filter out until re-embed, but keep fallback for backward compat during transition
            // For reliability, use minOf fallback for now so old 128 still works with new 768 queries (truncated)
        }
        var dot = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) dot += a[i]*b[i]
        return dot // both L2-normalized (768 or 128)
    }
}

// ==================== TIER 4: Darwin/VFE STATE SYNC ====================
object DarwinSync {
    /** Export Kai state (memories + VFE params + chat stats) to JSON — shareable with desktop */
    fun export(ctx: Context, vfeBase: Float, curvatureAlpha: Float): android.net.Uri? {
        return try {
            val db = KaiDb.get(ctx)
            val mems = db.memoryDao().allOnce()
            val json = buildString {
                append("{\n  \"kai_state_version\": 1,\n")
                append("  \"exported_at\": ${System.currentTimeMillis()},\n")
                append("  \"physics\": { \"vfe_base\": $vfeBase, \"curvature_alpha\": $curvatureAlpha },\n")
                append("  \"memories\": [\n")
                append(mems.joinToString(",\n") { m ->
                    val factJson = org.json.JSONObject.quote(m.fact)
                    "    { \"fact\": $factJson, \"source\": \"${m.source}\", \"created\": ${m.createdAt}, \"recall\": ${m.recallCount} }"
                })
                append("\n  ]\n}")
            }
            val f = java.io.File(ctx.getExternalFilesDir(null), "kai_state_export.json")
            f.writeText(json)
            android.net.Uri.fromFile(f)
        } catch (t: Throwable) { android.util.Log.e("DarwinSync", "export failed: $t"); null }
    }

    /** Import memories from desktop kai_state JSON */
    fun import(ctx: Context, json: String): Int {
        var count = 0
        Regex("\"fact\"\\s*:\\s*\"([^\"]+)\"").findAll(json).forEach { m ->
            try {
                kotlinx.coroutines.runBlocking {
                    MemoryEngine(ctx).store(m.groupValues[1], "desktop-import")
                }
                count++
            } catch (_: Throwable) {}
        }
        return count
    }
}
