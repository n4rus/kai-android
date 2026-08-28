package com.axiom.kai

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * KNOWLEDGE BASE (Block D) — ingested web/wiki passages, chunked + embedded,
 * recalled by cosine similarity. Separate DB file so chat/memory DBs are never migrated.
 */
@Entity(tableName = "chunks")
data class ChunkEntity(
    @PrimaryKey val id: String,
    val sourceTitle: String,   // page title or URL
    val url: String,
    val text: String,          // chunk content
    val embeddingJson: String, // 128-dim hashed embedding (same tech as memories)
    val createdAt: Long
)

@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks")
    fun allOnce(): List<ChunkEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(c: ChunkEntity)
    @Query("SELECT COUNT(*) FROM chunks")
    fun count(): Int
    @Query("DELETE FROM chunks WHERE url = :url")
    fun deleteByUrl(url: String)
    @Query("SELECT DISTINCT sourceTitle FROM chunks LIMIT 20")
    fun sources(): List<String>
}

@Database(entities = [ChunkEntity::class], version = 1)
abstract class KnowledgeDb : RoomDatabase() {
    abstract fun chunkDao(): ChunkDao
    companion object {
        @Volatile private var INSTANCE: KnowledgeDb? = null
        fun get(ctx: Context): KnowledgeDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(ctx.applicationContext, KnowledgeDb::class.java, "kai_knowledge.db")
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}

object Knowledge {
    private fun dao(ctx: Context) = KnowledgeDb.get(ctx).chunkDao()

    fun count(ctx: Context): Int = try { dao(ctx).count() } catch (_: Throwable) { 0 }

    /** Fetch a URL, split into ~800-char chunks, embed, store. Returns status line. */
    suspend fun ingestUrl(ctx: Context, url: String, maxChars: Int = 24000): String {
        val fetched = Tools.browse(url, maxLength = maxChars)
        if (fetched.startsWith("⚠")) return fetched
        val sourceTitle = url.substringAfter("//").substringBefore("/").take(60)
        dao(ctx).deleteByUrl(url)
        val chunks = chunkText(fetched, 800)
        var stored = 0
        for (c in chunks) {
            dao(ctx).insert(ChunkEntity(
                id = java.util.UUID.randomUUID().toString(),
                sourceTitle = sourceTitle,
                url = url,
                text = c,
                embeddingJson = TextEmbed.embed(ctx, c).joinToString(","),
                createdAt = System.currentTimeMillis()
            ))
            stored++
        }
        return "✓ ingested $stored chunks from $sourceTitle (${fetched.length} chars). Ask anything about it — I'll recall automatically, or run: /recall <query>"
    }

    /** Cosine top-k over chunks */
    suspend fun recall(ctx: Context, query: String, k: Int = 3): List<ChunkEntity> {
        val all = try { dao(ctx).allOnce() } catch (_: Throwable) { return emptyList() }
        if (all.isEmpty()) return emptyList()
        val qv = TextEmbed.embed(ctx, query)
        return all.map { it to TextEmbed.cosineJson(qv, it.embeddingJson) }
            .sortedByDescending { it.second }
            .take(k)
            .filter { it.second > 0.12f }
            .map { it.first }
    }

    /** [Knowledge] block for the system message (auto-recall) */
    suspend fun contextBlock(ctx: Context, query: String): String {
        val hits = recall(ctx, query, 2)
        if (hits.isEmpty()) return ""
        return "[Knowledge]\n" + hits.joinToString("\n\n") {
            "- (from ${it.sourceTitle}) ${it.text.take(500)}"
        } + "\n[/Knowledge]\n\n"
    }

    /** Split text into overlapping chunks on sentence-ish boundaries */
    internal fun chunkText(text: String, size: Int): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= size) return listOf(clean)
        val out = mutableListOf<String>()
        var start = 0
        while (start < clean.length && out.size < 40) {
            var end = (start + size).coerceAtMost(clean.length)
            // Prefer breaking at sentence end within the last 200 chars
            val window = clean.substring(start, end)
            val brk = maxOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? ")).coerceAtLeast(0)
            if (brk > size - 220) end = start + brk + 1
            out.add(clean.substring(start, end).trim())
            start = end - 80 // overlap
            if (end >= clean.length) break
        }
        return out.filter { it.isNotBlank() }
    }
}
